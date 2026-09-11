package android.os;
public class Handler {
    public Handler() {}
    public boolean post(Runnable work) { work.run(); return true; }
    public boolean postDelayed(Runnable work, long delay) { return true; }
}
