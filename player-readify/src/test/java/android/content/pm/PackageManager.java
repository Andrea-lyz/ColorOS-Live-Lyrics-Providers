package android.content.pm;
public class PackageManager {
    private final String version;
    public PackageManager(String version) { this.version = version; }
    public PackageInfo getPackageInfo(String name, int flags) {
        if (!"com.readin.app".equals(name)) throw new AssertionError("wrong package");
        PackageInfo info = new PackageInfo(); info.versionName = version; return info;
    }
}
