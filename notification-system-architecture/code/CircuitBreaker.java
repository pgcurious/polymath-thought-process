package com.notificationengine.resilience;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit Breaker Pattern Implementation
 *
 * Prevents cascading failures by detecting failing services and temporarily
 * rejecting requests to give the service time to recover.
 *
 * Used in Chapter 6: The Delivery Layer
 *
 * States:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Service is failing, reject requests immediately (fail fast)
 * - HALF_OPEN: Testing if service has recovered
 */
public class CircuitBreaker {

    public enum State {
        CLOSED,    // Normal operation
        OPEN,      // Failing, reject requests
        HALF_OPEN  // Testing recovery
    }

    private final AtomicReference<State> state;
    private final AtomicInteger failureCount;
    private final AtomicInteger successCount;
    private final AtomicLong openedAt;

    // Configuration
    private static final int FAILURE_THRESHOLD = 5;     // Open after 5 failures
    private static final int SUCCESS_THRESHOLD = 3;     // Close after 3 successes
    private static final long TIMEOUT_MS = 60_000;      // Try recovery after 1 minute

    public CircuitBreaker() {
        this.state = new AtomicReference<>(State.CLOSED);
        this.failureCount = new AtomicInteger(0);
        this.successCount = new AtomicInteger(0);
        this.openedAt = new AtomicLong(0);
    }

    /**
     * Executes an operation with circuit breaker protection
     *
     * @param operation The operation to execute
     * @return Result of the operation
     * @throws Exception if operation fails or circuit is open
     */
    public <T> T execute(Callable<T> operation) throws Exception {
        State currentState = state.get();

        if (currentState == State.OPEN) {
            // Check if timeout has elapsed
            if (System.currentTimeMillis() - openedAt.get() > TIMEOUT_MS) {
                // Transition to HALF_OPEN to test recovery
                state.compareAndSet(State.OPEN, State.HALF_OPEN);
                successCount.set(0);
                System.out.println("Circuit breaker: OPEN → HALF_OPEN (testing recovery)");
            } else {
                throw new CircuitBreakerOpenException(
                    "Circuit breaker is OPEN. Service unavailable."
                );
            }
        }

        try {
            T result = operation.call();
            onSuccess();
            return result;

        } catch (Exception e) {
            onFailure(e);
            throw e;
        }
    }

    /**
     * Called when operation succeeds
     */
    private void onSuccess() {
        failureCount.set(0);

        if (state.get() == State.HALF_OPEN) {
            int successes = successCount.incrementAndGet();

            if (successes >= SUCCESS_THRESHOLD) {
                // Service has recovered
                state.set(State.CLOSED);
                System.out.println("Circuit breaker: HALF_OPEN → CLOSED (recovered)");
            }
        }
    }

    /**
     * Called when operation fails
     */
    private void onFailure(Exception e) {
        int failures = failureCount.incrementAndGet();

        if (failures >= FAILURE_THRESHOLD) {
            // Too many failures, open the circuit
            state.set(State.OPEN);
            openedAt.set(System.currentTimeMillis());
            System.err.printf("Circuit breaker: CLOSED → OPEN (failures: %d)%n", failures);
        }

        if (state.get() == State.HALF_OPEN) {
            // Recovery failed, reopen circuit
            state.set(State.OPEN);
            openedAt.set(System.currentTimeMillis());
            System.err.println("Circuit breaker: HALF_OPEN → OPEN (recovery failed)");
        }
    }

    /**
     * Gets the current state of the circuit breaker
     */
    public State getState() {
        return state.get();
    }

    /**
     * Custom exception for circuit breaker open state
     */
    public static class CircuitBreakerOpenException extends Exception {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }

    /**
     * Example usage
     */
    public static void main(String[] args) throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker();

        // Simulated unreliable service
        Callable<String> unreliableService = new Callable<String>() {
            private int calls = 0;

            @Override
            public String call() throws Exception {
                calls++;

                // Fail for first 10 calls
                if (calls <= 10) {
                    throw new RuntimeException("Service unavailable");
                }

                // Succeed afterwards
                return "Success";
            }
        };

        // Test circuit breaker behavior
        for (int i = 1; i <= 20; i++) {
            try {
                String result = breaker.execute(unreliableService);
                System.out.printf("Call %d: %s (state: %s)%n", i, result, breaker.getState());
            } catch (Exception e) {
                System.err.printf("Call %d: %s (state: %s)%n", i, e.getMessage(), breaker.getState());
            }

            // Simulate time passing
            if (i == 10) {
                System.out.println("\n--- Waiting for circuit breaker timeout ---\n");
                Thread.sleep(60_100); // Wait for timeout
            } else {
                Thread.sleep(100);
            }
        }
    }
}
