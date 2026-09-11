package android.content;
public class Context {
    private final android.content.pm.PackageManager manager;
    public Context(String version) { manager = new android.content.pm.PackageManager(version); }
    public android.content.pm.PackageManager getPackageManager() { return manager; }
}
