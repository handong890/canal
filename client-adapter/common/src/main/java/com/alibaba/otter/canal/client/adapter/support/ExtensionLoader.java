package com.alibaba.otter.canal.client.adapter.support;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPI 类加载器
 *
 * @author rewerma 2018-8-19 下午11:30:49
 * @version 1.0.0
 */
public class ExtensionLoader<T> {

    private static final Logger                                      logger                     = LoggerFactory
        .getLogger(ExtensionLoader.class);

    private static final String                                      SERVICES_DIRECTORY         = "META-INF/services/";

    private static final String                                      CANAL_DIRECTORY            = "META-INF/canal/";

    private static final String                                      DEFAULT_CLASSLOADER_POLICY = "internal";

    private static final Pattern                                     NAME_SEPARATOR             = Pattern
        .compile("\\s*[,]+\\s*");

    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS          = new ConcurrentHashMap<>();

    private static final ConcurrentMap<Class<?>, Object>             EXTENSION_INSTANCES        = new ConcurrentHashMap<>();

    private final Class<?>                                           type;

    private final String                                             classLoaderPolicy;

    private final ConcurrentMap<Class<?>, String>                    cachedNames                = new ConcurrentHashMap<>();

    private final Holder<Map<String, Class<?>>>                      cachedClasses              = new Holder<>();

    private final ConcurrentMap<String, Holder<Object>>              cachedInstances            = new ConcurrentHashMap<>();

    private String                                                   cachedDefaultName;

    private ConcurrentHashMap<String, IllegalStateException>         exceptions                 = new ConcurrentHashMap<>();

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        return getExtensionLoader(type, DEFAULT_CLASSLOADER_POLICY);
    }

    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type, String classLoaderPolicy) {
        if (type == null) throw new IllegalArgumentException("Extension type == null");
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not extension, because WITHOUT @"
                                               + SPI.class.getSimpleName() + " Annotation!");
        }

        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type, classLoaderPolicy));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    private ExtensionLoader(Class<?> type, String classLoaderPolicy){
        this.type = type;
        this.classLoaderPolicy = classLoaderPolicy;
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        return cachedNames.get(extensionClass);
    }

    public ConcurrentHashMap<String, IllegalStateException> getExceptions() {
        return exceptions;
    }

    /**
     * 返回扩展点实例，如果没有指定的扩展点或是还没加载（即实例化）则返回<code>null</code>注意：此方法不会触发扩展点的加载
     * <p/>
     * 一般应该调用{@link #getExtension(String)}方法获得扩展，这个方法会触发扩展点加载
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (name == null || name.length() == 0) throw new IllegalArgumentException("Extension name == null");
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return (T) holder.get();
    }

    /**
     * 返回已经加载的扩展点的名字
     * <p/>
     * 一般应该调用{@link #getSupportedExtensions()}方法获得扩展，这个方法会返回所有的扩展点
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    /**
     * 返回指定名字的扩展
     *
     * @param name
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        if (name == null || name.length() == 0) throw new IllegalArgumentException("Extension name == null");
        if ("true".equals(name)) {
            return getDefaultExtension();
        }
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    instance = createExtension(name);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * 返回缺省的扩展，如果没有设置则返回<code>null</code>
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (null == cachedDefaultName || cachedDefaultName.length() == 0 || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (name == null || name.length() == 0) throw new IllegalArgumentException("Extension name == null");
        try {
            return getExtensionClass(name) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));
    }

    /**
     * 返回缺省的扩展点名，如果没有设置缺省则返回<code>null</code>
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * 编程方式添加新扩展点
     *
     * @param name 扩展点名
     * @param clazz 扩展点类
     * @throws IllegalStateException 要添加扩展点名已经存在
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " + clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " + clazz + "can not be interface!");
        }

        if (name == null || "".equals(name)) {
            throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
        }
        if (cachedClasses.get().containsKey(name)) {
            throw new IllegalStateException("Extension name " + name + " already existed(Extension " + type + ")!");
        }

        cachedNames.put(clazz, name);
        cachedClasses.get().put(name, clazz);
    }

    /**
     * 编程方式添加替换已有扩展点
     *
     * @param name 扩展点名
     * @param clazz 扩展点类
     * @throws IllegalStateException 要添加扩展点名已经存在
     * @deprecated 不推荐应用使用，一般只在测试时可以使用
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " + clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " + clazz + "can not be interface!");
        }

        if (name == null || "".equals(name)) {
            throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
        }
        if (!cachedClasses.get().containsKey(name)) {
            throw new IllegalStateException("Extension name " + name + " not existed(Extension " + type + ")!");
        }

        cachedNames.put(clazz, name);
        cachedClasses.get().put(name, clazz);
        cachedInstances.remove(name);
    }

    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " + type
                                            + ")  could not be instantiated: class could not be found");
        }
        try {
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, (T) clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " + type
                                            + ")  could not be instantiated: " + t.getMessage(),
                t);
        }
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) throw new IllegalArgumentException("Extension type == null");
        if (name == null) throw new IllegalArgumentException("Extension name == null");
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null)
            throw new IllegalStateException("No such extension \"" + name + "\" for " + type.getName() + "!");
        return clazz;
    }

    private Map<String, Class<?>> getExtensionClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    private String getJarDirectoryPath() {
        URL url = Thread.currentThread().getContextClassLoader().getResource("");
        if (url == null) {
            throw new IllegalStateException("failed to get class loader resource");
        }
        String dirtyPath = url.toString();
        String jarPath = dirtyPath.replaceAll("^.*file:/", ""); // removes
                                                                // file:/ and
                                                                // everything
                                                                // before it
        jarPath = jarPath.replaceAll("jar!.*", "jar"); // removes everything
                                                       // after .jar, if .jar
                                                       // exists in dirtyPath
        jarPath = jarPath.replaceAll("%20", " "); // necessary if path has
                                                  // spaces within
        if (!jarPath.endsWith(".jar")) { // this is needed if you plan to run
                                         // the app using Spring Tools Suit play
                                         // button.
            jarPath = jarPath.replaceAll("/classes/.*", "/classes/");
        }
        return Paths.get(jarPath).getParent().toString(); // Paths - from java 8
    }

    private Map<String, Class<?>> loadExtensionClasses() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                                                    + ": " + Arrays.toString(names));
                }
                if (names.length == 1) cachedDefaultName = names[0];
            }
        }

        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();

        // 1. lib folder，customized extension classLoader （jar_dir/lib）
        String dir = File.separator + this.getJarDirectoryPath() + File.separator + "lib";
        logger.info("extension classpath dir: " + dir);
        File externalLibDir = new File(dir);
        if (!externalLibDir.exists()) {
            externalLibDir = new File(
                File.separator + this.getJarDirectoryPath() + File.separator + "canal_client" + File.separator + "lib");
        }
        if (externalLibDir.exists()) {
            File[] files = externalLibDir.listFiles(new FilenameFilter() {

                @Override
                public boolean accept(File dir, String name) {
                    return name.endsWith(".jar");
                }
            });
            if (files != null) {
                for (File f : files) {
                    URL url = null;
                    try {
                        url = f.toURI().toURL();
                    } catch (MalformedURLException e) {
                        throw new RuntimeException("load extension jar failed!", e);
                    }

                    ClassLoader parent = Thread.currentThread().getContextClassLoader();
                    URLClassLoader localClassLoader;
                    if (classLoaderPolicy == null || "".equals(classLoaderPolicy)
                        || DEFAULT_CLASSLOADER_POLICY.equalsIgnoreCase(classLoaderPolicy)) {
                        localClassLoader = new URLClassLoader(new URL[] { url }, parent) {

                            @Override
                            public Class<?> loadClass(String name) throws ClassNotFoundException {
                                Class<?> c = findLoadedClass(name);
                                if (c != null) {
                                    return c;
                                }

                                if (name.startsWith("java.") || name.startsWith("org.slf4j.")
                                    || name.startsWith("org.apache.logging")
                                    || name.startsWith("org.apache.commons.logging.")) {
                                    // || name.startsWith("org.apache.hadoop."))
                                    // {
                                    c = super.loadClass(name);
                                }
                                if (c != null) return c;

                                try {
                                    // 先加载jar内的class，可避免jar冲突
                                    c = findClass(name);
                                } catch (ClassNotFoundException e) {
                                    c = null;
                                }
                                if (c != null) {
                                    return c;
                                }

                                return super.loadClass(name);
                            }

                            @Override
                            public Enumeration<URL> getResources(String name) throws IOException {
                                @SuppressWarnings("unchecked")
                                Enumeration<URL>[] tmp = (Enumeration<URL>[]) new Enumeration<?>[2];

                                tmp[0] = findResources(name); // local class
                                                              // path first
                                // tmp[1] = super.getResources(name);

                                return new CompoundEnumeration<>(tmp);
                            }
                        };
                    } else {
                        localClassLoader = new URLClassLoader(new URL[] { url }, parent);
                    }

                    loadFile(extensionClasses, CANAL_DIRECTORY, localClassLoader);
                    loadFile(extensionClasses, SERVICES_DIRECTORY, localClassLoader);
                }
            }
        }
        // 2. load inner extension class with default classLoader
        ClassLoader classLoader = findClassLoader();
        loadFile(extensionClasses, CANAL_DIRECTORY, classLoader);
        loadFile(extensionClasses, SERVICES_DIRECTORY, classLoader);

        return extensionClasses;
    }

    public static class CompoundEnumeration<E> implements Enumeration<E> {

        private Enumeration<E>[] enums;
        private int              index = 0;

        public CompoundEnumeration(Enumeration<E>[] enums){
            this.enums = enums;
        }

        private boolean next() {
            while (this.index < this.enums.length) {
                if (this.enums[this.index] != null && this.enums[this.index].hasMoreElements()) {
                    return true;
                }

                ++this.index;
            }

            return false;
        }

        public boolean hasMoreElements() {
            return this.next();
        }

        public E nextElement() {
            if (!this.next()) {
                throw new NoSuchElementException();
            } else {
                return this.enums[this.index].nextElement();
            }
        }
    }

    private void loadFile(Map<String, Class<?>> extensionClasses, String dir, ClassLoader classLoader) {
        String fileName = dir + type.getName();
        try {
            Enumeration<URL> urls;
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    URL url = urls.nextElement();
                    try {
                        BufferedReader reader = null;
                        try {
                            reader = new BufferedReader(new InputStreamReader(url.openStream(), "utf-8"));
                            String line = null;
                            while ((line = reader.readLine()) != null) {
                                final int ci = line.indexOf('#');
                                if (ci >= 0) line = line.substring(0, ci);
                                line = line.trim();
                                if (line.length() > 0) {
                                    try {
                                        String name = null;
                                        int i = line.indexOf('=');
                                        if (i > 0) {
                                            name = line.substring(0, i).trim();
                                            line = line.substring(i + 1).trim();
                                        }
                                        if (line.length() > 0) {
                                            Class<?> clazz = classLoader.loadClass(line);
                                            // Class<?> clazz =
                                            // Class.forName(line, true,
                                            // classLoader);
                                            if (!type.isAssignableFrom(clazz)) {
                                                throw new IllegalStateException(
                                                    "Error when load extension class(interface: " + type
                                                                                + ", class line: " + clazz.getName()
                                                                                + "), class " + clazz.getName()
                                                                                + "is not subtype of interface.");
                                            } else {
                                                try {
                                                    clazz.getConstructor(type);
                                                } catch (NoSuchMethodException e) {
                                                    clazz.getConstructor();
                                                    String[] names = NAME_SEPARATOR.split(name);
                                                    if (names != null && names.length > 0) {
                                                        for (String n : names) {
                                                            if (!cachedNames.containsKey(clazz)) {
                                                                cachedNames.put(clazz, n);
                                                            }
                                                            Class<?> c = extensionClasses.get(n);
                                                            if (c == null) {
                                                                extensionClasses.put(n, clazz);
                                                            } else if (c != clazz) {
                                                                cachedNames.remove(clazz);
                                                                throw new IllegalStateException(
                                                                    "Duplicate extension " + type.getName() + " name "
                                                                                                + n + " on "
                                                                                                + c.getName() + " and "
                                                                                                + clazz.getName());
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Throwable t) {
                                        IllegalStateException e = new IllegalStateException(
                                            "Failed to load extension class(interface: " + type + ", class line: "
                                                                                            + line + ") in " + url
                                                                                            + ", cause: "
                                                                                            + t.getMessage(),
                                            t);
                                        exceptions.put(line, e);
                                    }
                                }
                            } // end of while read lines
                        } finally {
                            if (reader != null) {
                                reader.close();
                            }
                        }
                    } catch (Throwable t) {
                        logger.error("Exception when load extension class(interface: " + type + ", class file: " + url
                                     + ") in " + url,
                            t);
                    }
                } // end of while urls
            }
        } catch (Throwable t) {
            logger.error(
                "Exception when load extension class(interface: " + type + ", description file: " + fileName + ").",
                t);
        }
    }

    private static ClassLoader findClassLoader() {
        return ExtensionLoader.class.getClassLoader();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

    private static class Holder<T> {

        private volatile T value;

        private void set(T value) {
            this.value = value;
        }

        private T get() {
            return value;
        }

    }
}
