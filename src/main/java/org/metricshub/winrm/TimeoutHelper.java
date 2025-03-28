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

import java.util.concurrent.TimeoutException;

/**
 * for stagged timeout handling.
 *
 */
public class TimeoutHelper {

	private TimeoutHelper() {}

	/**
	 * Get the remaining time before timeout.
	 *
	 * @param timeout Timeout in milliseconds. (throws an IllegalArgumentException if negative or zero)
	 * @param start start time in milliseconds. (throws an IllegalArgumentException if negative or zero)
	 * @return The remaining time
	 * @throws TimeoutException if there's no time left
	 */
	public static long getRemainingTime(final long timeout, final long start, final String message)
		throws TimeoutException {
		long remaining = start + timeout - Utils.getCurrentTimeMillis();
		if (remaining < 0) {
			throw new TimeoutException(message);
		}
		return remaining;
	}

	/**
	 * Sub to make the thread sleep in a stepped delay.
	 * <p>Delay:
	 * <ul>
	 * <li>50 milliseconds for time from start &lt; 500 milliseconds</li>
	 * <li>100 milliseconds for time from start between 500 milliseconds and 1 second</li>
	 * <li>250 milliseconds for time from start between 1 second and 5 seconds</li>
	 * <li>500 milliseconds for time from start between 5 seconds and 10 seconds</li>
	 * <li>1 second for time from start between 10 seconds and 30 seconds</li>
	 * <li>3 seconds otherwise</li>
	 * </ul>
	 *
	 * @param timeout Timeout in milliseconds
	 * @param start start time in milliseconds
	 * @param message The timeout error message
	 *
	 * @throws TimeoutException when times out
	 *
	 */
	public static void stagedSleep(final long timeout, final long start, final String message) throws TimeoutException {
		// Check we still have some time on our hands
		getRemainingTime(timeout, start, message);

		final long elapsedTime = Utils.getCurrentTimeMillis() - start;
		try {
			if (elapsedTime < 500) {
				Utils.sleep(50);
			} else if (elapsedTime < 1000) {
				Utils.sleep(100);
			} else if (elapsedTime < 5000) {
				Utils.sleep(250);
			} else if (elapsedTime < 10000) {
				Utils.sleep(500);
			} else if (elapsedTime < 30000) {
				Utils.sleep(1000);
			} else {
				Utils.sleep(3000);
			}
		} catch (InterruptedException e) {
			throw new TimeoutException(message);
		}
	}
}
