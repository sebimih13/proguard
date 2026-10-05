package demo;

/** A small application for generating real helpful NPEs after obfuscation. */
public class InheritanceCrash
{
    interface Model { Object getTreeElementsForRoot(Object root); }
    interface Root { Model sharedModel = null; }
    interface Left extends Root {}
    interface Right extends Root {}
    interface Diamond extends Left, Right {}
    static class Stats { int count; }
    static class Parent
    {
        Model inheritedModel;
        Object lock;
        Object[] items;
        Stats stats;
    }
    static class Child extends Parent implements Diamond
    {
        Model ownModel;
        Object own() { return ownModel.getTreeElementsForRoot(null); }
        Object inherited() { return inheritedModel.getTreeElementsForRoot(null); }
        Object shared() { return sharedModel.getTreeElementsForRoot(null); }
        Object arrayLength() { return items.length; }
        Object read() { return stats.count; }
        Object write() { stats.count = 1; return null; }
        Object monitor() { synchronized (lock) { return null; } }
    }
    static class Hidden extends Parent
    {
        Model inheritedModel;
        Object hidden() { return inheritedModel.getTreeElementsForRoot(null); }
        Object base() { return super.inheritedModel.getTreeElementsForRoot(null); }
    }
    public static void main(String[] args)
    {
        Child child = new Child();
        switch (args[0])
        {
            case "own": child.own(); break;
            case "inherited": child.inherited(); break;
            case "shared": child.shared(); break;
            case "array": child.arrayLength(); break;
            case "read": child.read(); break;
            case "write": child.write(); break;
            case "monitor": child.monitor(); break;
            case "hidden": new Hidden().hidden(); break;
            case "base": new Hidden().base(); break;
            default: throw new IllegalArgumentException(args[0]);
        }
    }
}
