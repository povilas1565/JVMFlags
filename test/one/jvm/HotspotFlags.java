package one.jvm;

import sun.misc.Unsafe;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.NoSuchElementException;

public class HotspotFlags {
    private static Unsafe unsafe = getUnsafe();

    private ElfSymbolTable symtab;
    private long baseAddress;

    public HotspotFlags() throws IOException {
        checkEnvironment();

        String maps = findJvmMaps();
        String jvmLibrary = maps.substring(maps.lastIndexOf(' ') + 1);
        long jvmAddress = Long.parseLong(maps.substring(0, maps.indexOf('-')), 16);

        ElfReader elfReader = new ElfReader(jvmLibrary);
        ElfSection symtab = elfReader.section(".symtab");
        if (!(symtab instanceof ElfSymbolTable)) {
            throw new IOException(".symtab section not found");
        }

        this.symtab = (ElfSymbolTable) symtab;
        this.baseAddress = elfReader.elf64() ? jvmAddress : 0;
    }

    private static Unsafe getUnsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new AssertionError("Unable to get Unsafe");
        }
    }

    private static void checkEnvironment() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            throw new AssertionError("Works on Linux only");
        }
        if (!System.getProperty("java.vm.name", "").toLowerCase().contains("hotspot")) {
            throw new AssertionError("Works on HotSpot JVM only");
        }
    }

    private static String findJvmMaps() throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"));
        try {
            for (String s; (s = reader.readLine()) != null; ) {
                if (s.endsWith("/libjvm.so")) {
                    return s;
                }
            }
            throw new IOException("libjvm.so not found");
        } finally {
            reader.close();
        }
    }

    public ElfSymbol findSymbol(String name) {
        for (ElfSymbol symbol : symtab) {
            if (name.equals(symbol.name()) && symbol.type() == ElfSymbol.STT_OBJECT) {
                return symbol;
            }
        }
        throw new NoSuchElementException("Symbol not found: " + name);
    }

    public int getIntFlag(String name) {
        ElfSymbol symbol = findSymbol(name);
        return unsafe.getInt(baseAddress + symbol.value());
    }

    public boolean getBooleanFlag(String name) {
        return getIntFlag(name) != 0;
    }

    public void setIntFlag(String name, int value) {
        ElfSymbol symbol = findSymbol(name);
        unsafe.putInt(baseAddress + symbol.value(), value);
    }

    public void setBooleanFlag(String name, boolean value) {
        setIntFlag(name, value ? 1 : 0);
    }

    private static long testHashCode() {
        int sum = 0;
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < 10000000; i++) {
            sum ^= new Object().hashCode();
        }

        long endTime = System.currentTimeMillis();

        if (sum == 0) System.gc();  // avoid compiler optimization
        return endTime - startTime;
    }

    public static void main(String[] args) throws Exception {
        HotspotFlags flags = new HotspotFlags();

        boolean prevUseBiasedLocking = flags.getBooleanFlag("UseBiasedLocking");
        flags.setBooleanFlag("UseBiasedLocking", false);

        System.out.println("hashCode algorithm = " + flags.getIntFlag("hashCode"));
        for (int i = 0; i < 5; i++) {
            System.out.println(testHashCode());
        }

        flags.setIntFlag("hashCode", 5);

        System.out.println("hashCode algorithm = " + flags.getIntFlag("hashCode"));
        for (int i = 0; i < 5; i++) {
            System.out.println(testHashCode());
        }

        flags.setBooleanFlag("UseBiasedLocking", prevUseBiasedLocking);

        System.out.println("Changing TraceClassLoading policy...");
        flags.setBooleanFlag("TraceClassLoading", true);

        Class.forName("java.net.ServerSocket");  // extra VM info will be printed
    }
}
