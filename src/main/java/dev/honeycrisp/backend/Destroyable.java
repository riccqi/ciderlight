package dev.honeycrisp.backend;

/** A GPU object whose native release must wait until in-flight frames stop using it. */
@FunctionalInterface
public interface Destroyable {
    void destroy();
}
