package demo;
public class OptimizedCrash {
    public interface Target { void mappedCall(); }
    public static class State { public Target field; }
    static volatile Target target;
    private static void leaf(State state) { state.field.mappedCall(); }
    private static void bridge(State state) { leaf(state); }
    private static void entry(State state) { bridge(state); }
    public static void main(String[] args) {
        if (args.length != 0) target = null;
        State state = new State();
        state.field = target;
        entry(state);
    }
}
