/*
 *    Copyright 2022 Whilein
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package w.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * @author whilein
 */
public final class AgentInstrumentation {

    private static final long PID;
    private static final String JAVA_EXECUTABLE;

    private static final Instrumentation INSTRUMENTATION;

    static {
        final ProcessHandle process = ProcessHandle.current();

        PID = process.pid();

        JAVA_EXECUTABLE = process.info().command()
                .orElseGet(() -> System.getProperty("JAVA_HOME") // or default
                                 + "/bin/java");

        try {
            final Path agentJar = createAgentJar();

            final Instrumentation instrumentation = runAgent(agentJar);

            if (instrumentation == null) {
                throw new IllegalStateException("Unable to install java agent");
            }

            INSTRUMENTATION = instrumentation;
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Создать Jar-ник с одним классом и манифестом,
     * для того, чтобы запустить его через {@link #runAgent(Path)}
     *
     * @return путь нового Jar-ника
     * @throws IOException ошибка при создании временного файла
     */
    private static Path createAgentJar() throws IOException {
        final Path temporary = Files.createTempFile("wcommons", ".jar");

        final String agentMainName = "w.agent.AgentMain";

        final Manifest manifest = new Manifest();
        final Attributes manifestAttributes = manifest.getMainAttributes();
        manifestAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifestAttributes.put(new Attributes.Name("Can-Redefine-Classes"), "true");
        manifestAttributes.put(new Attributes.Name("Can-Retransform-Classes"), "true");
        manifestAttributes.put(new Attributes.Name("Agent-Class"), agentMainName);
        manifestAttributes.put(new Attributes.Name("Main-Class"), agentMainName);

        try (final JarOutputStream jar = new JarOutputStream(Files.newOutputStream(temporary), manifest)) {
            final String mainClassName = agentMainName.replace('.', '/') + ".class";

            final ZipEntry mainClass = new ZipEntry(mainClassName);

            jar.putNextEntry(mainClass);

            try (final InputStream resource = AgentInstrumentation.class.getClassLoader()
                    .getResourceAsStream(mainClassName)) {
                if (resource == null) {
                    throw new IllegalStateException("Cannot find " + mainClassName
                                                    + " AgentInstrumentation class loader");
                }

                jar.write(resource.readAllBytes());
            }
        }

        return temporary;
    }

    /**
     * Запустить Jar, ибо нельзя подключать агент в той же JVM, начиная с Java 9
     *
     * @param agent Путь до Jar агента
     * @throws IOException          ошибка, если не удалось запустить процесс
     * @throws InterruptedException ошибка, если не удалось дождаться завершения процесса
     */
    private static Instrumentation runAgent(final Path agent) throws IOException, InterruptedException {
        final URL cp = AgentInstrumentation.class.getProtectionDomain().getCodeSource()
                .getLocation();

        final String[] args = new String[]{
                JAVA_EXECUTABLE,
                "-cp",
                cp.getFile(),
                "-jar",
                agent.toAbsolutePath().toString(),
                String.valueOf(PID)
        };

        final Process process = new ProcessBuilder(args).start();

        try (final InputStream out = process.getInputStream()) {
            out.transferTo(System.out);
        }

        try (final InputStream err = process.getErrorStream()) {
            err.transferTo(System.err);
        }

        process.waitFor();

        try {
            final Class<?> agentMain = ClassLoader.getSystemClassLoader().loadClass("w.agent.AgentMain");

            final Field field = agentMain.getDeclaredField("instrumentation");
            field.setAccessible(true);

            return (Instrumentation) field.get(null);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void redefineModule(
            final Module module,
            final Set<Module> extraReads,
            final Map<String, Set<Module>> extraExports,
            final Map<String, Set<Module>> extraOpens,
            final Set<Class<?>> extraUses,
            final Map<Class<?>, List<Class<?>>> extraProvides
    ) {
        INSTRUMENTATION.redefineModule(module, extraReads, extraExports, extraOpens, extraUses, extraProvides);
    }

}
