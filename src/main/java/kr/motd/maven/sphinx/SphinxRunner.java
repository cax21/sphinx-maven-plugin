package kr.motd.maven.sphinx;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.python.core.Py;
import org.python.core.PyObject;
import org.python.core.PySystemState;
import org.python.util.PythonInterpreter;

import net.sourceforge.plantuml.UmlDiagram;

/**
 * Sphinx Runner.
 */
public abstract class SphinxRunner {

    private static final String DIST_PREFIX =
            SphinxRunner.class.getPackage().getName().replace('.', '/') + "/dist/";

    /** PlantUML Jar Exec Script for sphinx-plantuml plugin. */
    private final String plantUmlCommand;

    /**
     * Initialize Environment to execute the plugin.
     */
    protected SphinxRunner(File sphinxSourceDirectory) {
        if (sphinxSourceDirectory == null) {
            throw new IllegalArgumentException("sphinxSourceDirectory is empty.");
        }

        extractSphinx(sphinxSourceDirectory);
        final File plantUmlJar = findPlantUmlJar();

        plantUmlCommand = "java -jar " + plantUmlJar.getPath().replace("\\", "\\\\");
        log("PlantUML command: " + plantUmlCommand);

        // use headless mode for AWT (prevent "Launcher" app on Mac OS X)
        System.setProperty("java.awt.headless", "true");

        // this setting supposedly allows GCing of jython-generated classes but I'm
        // not sure if this setting has any effect on newer jython versions anymore
        System.setProperty("python.options.internalTablesImpl", "weak");

        PySystemState engineSys = new PySystemState();
        engineSys.path.append(Py.newString(sphinxSourceDirectory.getPath()));
        Py.setSystemState(engineSys);
        log("Path: " + engineSys.path);
    }

    public void destroy() {
        Py.getSystemState().cleanup();
    }

    protected abstract void log(String msg);

    /**
     * Execute Python Script using Jython Python Interpreter.
     *
     * @param script to execute
     * @param functionName the function name to which arguments have to be passed.
     */
    private int executePythonScript(
            String script, String functionName, List<String> args, boolean resultExpected) {

        log("args: " + args);

        PythonInterpreter pi = new PythonInterpreter();

        // Set some necessary environment variables.
        pi.exec("from os import putenv");
        PyObject env = pi.get("putenv");

        // Set the locale for consistency.
        env.__call__(Py.java2py("LANG"), Py.java2py("en_US.UTF-8"));
        env.__call__(Py.java2py("LC_ALL"), Py.java2py("en_US.UTF-8"));

        // Set the command that runs PlantUML.
        env.__call__(Py.java2py("plantuml"), Py.java2py(plantUmlCommand));

        // babel/localtime/_unix.py attempts to use os.readlink() which is unavailable in some OSes.
        // Setting the 'TZ' environment variable works around this problem.
        env.__call__(Py.java2py("TZ"), Py.java2py("UTC"));

        // Execute the script, finally.
        pi.exec(script);

        PyObject func = pi.get(functionName);
        PyObject ret = func.__call__(Py.java2py(args));
        int result = 0;
        if (resultExpected) {
            result = Py.tojava(ret, Integer.class);
        }

        pi.close();
        pi.cleanup();

        return result;
    }

    /**
     * Execute Sphinx Documentation Builder.
     */
    public int runSphinx(List<String> args) {
        String invokeSphinxScript = "from sphinx import build_main";
        String functionName = "build_main";
        return executePythonScript(invokeSphinxScript, functionName, args, true);
    }

    /**
     * Unpack Sphinx zip file.
     */
    private void extractSphinx(File sphinxSourceDirectory) {
        log("Extracting Sphinx into: " + sphinxSourceDirectory);

        try {
            final JarFile jar = new JarFile(findPluginJar(), false);
            final byte[] buf = new byte[65536];
            for (Enumeration<JarEntry> i = jar.entries(); i.hasMoreElements();) {
                final JarEntry e = i.nextElement();
                if (!e.getName().startsWith(DIST_PREFIX)) {
                    continue;
                }

                final File f = new File(sphinxSourceDirectory + File.separator +
                                        e.getName().substring(DIST_PREFIX.length()));

                if (e.isDirectory()) {
                    if (!f.mkdirs() && !f.exists()) {
                        throw new SphinxException("failed to create a directory: " + f);
                    }
                    continue;
                }

                if (f.exists()) {
                    if (f.length() == e.getSize()) {
                        // Very likely that the entry has been extracted in a previous run.
                        continue;
                    }

                    if (!f.delete()) {
                        throw new SphinxException("failed to delete a file: " + f);
                    }
                }

                final File tmpF = new File(f.getParentFile(), ".tmp-" + f.getName());
                boolean success = false;
                try (InputStream in = jar.getInputStream(e);
                     OutputStream out = new FileOutputStream(tmpF)) {

                    for (;;) {
                        int readBytes = in.read(buf);
                        if (readBytes < 0) {
                            break;
                        }
                        out.write(buf, 0, readBytes);
                    }

                    success = true;
                } finally {
                    if (!success) {
                        tmpF.delete();
                    }
                }

                if (!tmpF.renameTo(f)) {
                    throw new SphinxException("failed to rename a file: " + tmpF + " -> " + f.getName());
                }
            }
        } catch (Exception e) {
            throw new SphinxException("failed to extract Sphinx into: " + sphinxSourceDirectory, e);
        }
    }

    private File findPluginJar() {
        return findJar(SphinxRunner.class, "the plugin JAR");
    }

    private File findPlantUmlJar() {
        return findJar(UmlDiagram.class, "PlantUML JAR");
    }

    private File findJar(Class<?> type, String name) {
        final CodeSource codeSource = type.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            throw new SphinxException(
                    "failed to get the location of " + name + " (CodeSource not available)");
        }

        final URL url = codeSource.getLocation();
        log(name + ": " + url);
        if (!"file".equals(url.getProtocol()) || !url.getPath().toLowerCase(Locale.US).endsWith(".jar")) {
            throw new SphinxException(
                    "failed to get the location of " + name + " (unexpected URL: " + url + ')');
        }

        File f;
        try {
            f = new File(url.toURI());
        } catch (URISyntaxException ignored) {
            f = new File(url.getPath());
        }

        return f;
    }
}
