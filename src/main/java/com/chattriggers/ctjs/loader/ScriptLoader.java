package com.chattriggers.ctjs.loader;

import com.chattriggers.ctjs.CTJS;
import com.chattriggers.ctjs.imports.Import;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.commons.io.FileUtils;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.*;
import java.util.*;

public class ScriptLoader {
    private List<Import> loadedImports;
    private ScriptEngine scriptEngine;
    private Invocable invocableEngine;

    private Boolean hasProvidedLibsTick;
    private Boolean hasCustomLibsTick;
    private Boolean hasProvidedLibsWorld;
    private Boolean hasCustomLibsWorld;

    //TODO: Move to config?
    private ArrayList<String> illegalLines;

    public ScriptLoader() {
        this.scriptEngine = CTJS.getInstance().getScriptEngine();
        this.invocableEngine = CTJS.getInstance().getInvocableEngine();

        this.hasProvidedLibsTick = true;
        this.hasCustomLibsTick = true;
        this.hasProvidedLibsWorld = true;
        this.hasCustomLibsWorld = true;

        this.illegalLines = new ArrayList<>(Arrays.asList(
                "module.export", "load(\"http"
        ));

        //Save provided libs script from jar to os filesystem - replaces every time
        saveResource("/providedLibs.js", new File("./mods/ChatTriggers/libs/chattriggers-provided-libs.js"), true);
        //Save custom libs script from jar to os filesystem - doesn't replace
        saveResource("/customLibs.js", new File("./mods/ChatTriggers/libs/chattriggers-custom-libs.js"), false);

        //Load the imports (This compiles them and loads them)
        loadImports();
        //Load assets (Puts images into ctjs resource location
        loadAssets();

        try {
            scriptEngine.eval(getProvidedLibsScript());
            scriptEngine.eval(getCustomLibsScript());

            for (Import customImport : this.loadedImports) {
                scriptEngine.eval(customImport.getScript());
            }
        } catch (ScriptException e) {
            e.printStackTrace();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (this.hasProvidedLibsTick) {
            try {
                invocableEngine.invokeFunction("updateProvidedLibsTick");
            } catch (ScriptException | NoSuchMethodException exc) {
                this.hasProvidedLibsTick = false;
                exc.printStackTrace();
            }
        }

        if (this.hasCustomLibsTick) {
            try {
                invocableEngine.invokeFunction("updateCustomLibsTick");
            } catch (ScriptException | NoSuchMethodException exc) {
                this.hasCustomLibsTick = false;
                exc.printStackTrace();
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onWorldLoad(WorldEvent.Load event) {
        if (this.hasProvidedLibsWorld) {
            try {
                invocableEngine.invokeFunction("updateProvidedLibsWorld");
            } catch (ScriptException | NoSuchMethodException exc) {
                this.hasProvidedLibsWorld = false;
                exc.printStackTrace();
            }
        }

        if (this.hasCustomLibsWorld) {
            try {
                invocableEngine.invokeFunction("updateCustomLibsWorld");
            } catch (ScriptException | NoSuchMethodException exc) {
                this.hasCustomLibsWorld = false;
                exc.printStackTrace();
            }
        }
    }

    private void loadImports() {
        this.loadedImports = getCompiledImports();
    }

    /**
     * Save a resource to the OS's filesystem from inside the jar
     * @param resourceName name of the file inside the jar
     * @param outputFile file to save to
     * @param replace whether or not to replace the file being saved to
     */
    public void saveResource(String resourceName, File outputFile, boolean replace) {
        if (resourceName == null || resourceName.equals("")) {
            throw new IllegalArgumentException("ResourcePath cannot be null or empty");
        }

        resourceName = resourceName.replace('\\', '/');
        InputStream in = this.getClass().getResourceAsStream(resourceName);

        if (in == null) {
            throw new IllegalArgumentException("The embedded resource '" + resourceName + "' cannot be found.");
        }

        File outDir = outputFile.getParentFile();

        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        try {
            if (!outputFile.exists() || replace) {
                OutputStream out = new FileOutputStream(outputFile);
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.close();
                in.close();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Gets the script that provides imports basic libraries.
     * @return a string of the compiled script
     */
    public String getProvidedLibsScript() {
        try {
            return compileScripts(new File("./mods/ChatTriggers/libs/chattriggers-provided-libs.js"));
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Gets the script that provides imports custom libraries.
     * @return a string of the compiled script
     */
    public String getCustomLibsScript() {
        try {
            return compileScripts(new File("./mods/ChatTriggers/libs/chattriggers-custom-libs.js"));
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Compiles all text from multiple files
     * into a singular string for loading.
     * @param files a list of files to be compiled
     * @return the string after compilation
     * @throws IOException thrown if a file doesn't exist
     */
    public String compileScripts(File... files) throws IOException {
        StringBuilder compiledScript = new StringBuilder();

        for (File file : files) {
            if (!file.isFile() || !file.exists() || !file.getName().endsWith(".js")) continue;

            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;

            parseScript:
            while ((line = br.readLine()) != null) {
                for (String illegalLine : illegalLines) {
                    if (line.contains(illegalLine)) continue parseScript;
                }

                compiledScript.append(line).append("\n");
            }
        }

        return compiledScript.toString();
    }

    /**
     * Helper method to get all the folders in a directory,
     * used to get import folders.
     * @param directory directory to search through
     * @return a list of files in directory, or null if not a directory
     */
    public List<File> getFoldersInDirectory(File directory) {
        if (!directory.isDirectory()) return null;

        List<File> filesToReturn = new ArrayList<>();
        for (File file : directory.listFiles()) {
            if (file.isDirectory()) filesToReturn.add(file);
        }

        return filesToReturn;
    }

    /**
     * Helper method which gets all the imports
     * compiled into strings.
     * @return a list of imports, all compiled
     */
    public List<Import> getCompiledImports() {
        List<Import> compiledImports = new ArrayList<>();

        File importsDir = new File("./mods/ChatTriggers/Imports/");
        importsDir.mkdirs();

        for (File importDir : getFoldersInDirectory(importsDir)) {
            try {
                Import newImport = new Import(importDir.getName(), compileScripts(importDir.listFiles()));
                compiledImports.add(newImport);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return compiledImports;
    }

    private void loadAssets() {
        File importsDir = new File("./mods/ChatTriggers/Imports/");
        File toCopyDir = CTJS.getInstance().getAssetsDir();

        for (File importDir : getFoldersInDirectory(importsDir)) {
            File assetsFolder = new File(importDir, "assets");

            if (!assetsFolder.exists() || assetsFolder.isFile()) continue;

            for (File asset : assetsFolder.listFiles()) {
                try {
                    FileUtils.copyFileToDirectory(asset, toCopyDir);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}