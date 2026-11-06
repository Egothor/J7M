// Copyright (c) 2026, Leo Galambos and the Egothor team
// SPDX-License-Identifier: BSD-4-Clause
package example.hello;

import org.j7m.middleware.annotations.ConfigValue;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mApplication;
import org.j7m.middleware.annotations.Shutdown;
import org.j7m.middleware.annotations.Startup;

/** Greeting application whose generated component metadata is loaded by the platform. */
@J7mApplication(group = "example", name = "hello", version = "1.0.0")
public final class HelloApplication {
    private final String message;

    /**
     * Receives safe application configuration from the host.
     * @param message greeting configured by the developer or operator
     */
    @Inject
    public HelloApplication(@ConfigValue(value = "greeting.message", defaultValue = "Hello J7M") final String message) {
        this.message = message;
    }

    /** Reports actual application startup after dependency construction. */
    @Startup public void start() { System.out.println("HELLO_STARTED " + this.message); }

    /** Reports actual application shutdown before the owned component is released. */
    @Shutdown public void stop() { System.out.println("HELLO_STOPPED"); }
}
