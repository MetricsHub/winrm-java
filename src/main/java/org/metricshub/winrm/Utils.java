package org.metricshub.winrm;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright 2023 - 2024 Metricshub
 * ჻჻჻჻჻჻
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱
 */

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Utils {

	public static final String EMPTY = "";
	public static final String NEW_LINE = "\n";

	/**
	 * Check if the required argument is not null.
	 *
	 * @param argument
	 * @param name
	 * @throws IllegalArgumentException if the argument is null
	 */
	public static <T> void checkNonNull(final T argument, final String name) {
		if (argument == null) {
			throw new IllegalArgumentException(name + " must not be null.");
		}
	}

	/**
	 * @param value The value to check
	 * @return whether the value is null, empty or contains only blank chars
	 */
	public static boolean isBlank(String value) {
		return value == null || isEmpty(value);
	}

	/**
	 * @param value The value to check
	 * @return whether the value is empty of non-blank chars
	 * @throws NullPointerException if value is <em>null</em>
	 */
	public static boolean isEmpty(String value) {
		return value.trim().isEmpty();
	}

	/**
	 * @param value The value to check
	 * @return whether the value is not null, nor empty nor contains only blank chars
	 */
	public static boolean isNotBlank(final String value) {
		return !isBlank(value);
	}

	/**
	 * @return the name of the local computer (or "localhost" if it can't be determined)
	 */
	public static String getComputerName() {
		final String computerName = System.getenv("COMPUTERNAME");
		if (computerName == null) {
			return "localhost";
		}
		return computerName;
	}

	/**
	 * Wrapper for Thread.sleep(millis)
	 *
	 * @param millis Time to sleep (in milliseconds)
	 * @throws InterruptedException
	 */
	public static void sleep(final long millis) throws InterruptedException {
		Thread.sleep(millis);
	}

	/**
	 * Check if the required argument is not negative or zero.
	 *
	 * @param argument
	 * @param name
	 * @throws IllegalArgumentException if the argument is null
	 */
	public static void checkArgumentNotZeroOrNegative(final long argument, final String name) {
		if (argument <= 0) {
			throw new IllegalArgumentException(String.format("%s=%d must not be negative or zero.", name, argument));
		}
	}

	/**
	 * Get the current time in Milliseconds.
	 * @return the current time in Milliseconds.
	 */
	public static long getCurrentTimeMillis() {
		return System.currentTimeMillis();
	}

	/**
	 * Check if the required argument is not blank (null or empty).
	 *
	 * @param argument
	 * @param name
	 * @throws IllegalArgumentException if the argument is null
	 */
	public static void checkNonBlank(final String argument, final String name) {
		if (isBlank(argument)) {
			throw new IllegalArgumentException(name + " must not be null or empty.");
		}
	}

	/**
	 * Run the given {@link Callable} using the passed timeout in seconds.
	 *
	 * @param <T>
	 * @param callable
	 * @param timeout
	 * @return {@link T} result returned by the callable
	 *
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws TimeoutException
	 */
	public static <T> T execute(Callable<T> callable, long timeout)
		throws InterruptedException, ExecutionException, TimeoutException {
		ExecutorService executorService = Executors.newSingleThreadExecutor();
		Future<T> future = executorService.submit(callable);

		try {
			return future.get(timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw e;
		} catch (TimeoutException e) {
			future.cancel(true);
			throw e;
		} finally {
			executorService.shutdownNow();
		}
	}

	/**
	 * Check if the required field is not null.
	 *
	 * @param field
	 * @param name
	 * @throws IllegalStateException if the argument is null
	 */
	public static <T> void checkNonNullField(final T field, final String name) {
		if (field == null) {
			throw new IllegalStateException(name + " must not be null.");
		}
	}
}
