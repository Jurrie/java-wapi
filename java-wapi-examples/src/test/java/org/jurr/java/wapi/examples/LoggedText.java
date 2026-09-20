package org.jurr.java.wapi.examples;

import java.util.stream.Collectors;

import com.github.valfirst.slf4jtest.TestLoggerFactory;

/** What an example logged, read through slf4j-test. */
final class LoggedText
{
	private LoggedText()
	{
	}

	/**
	 * @param type class whose logger to read
	 * @return the formatted messages of all threads, in the order they were logged, each followed by
	 *         a line separator
	 */
	static String of(final Class<?> type)
	{
		return TestLoggerFactory.getTestLogger(type).getAllLoggingEvents().stream()
				.map(event -> event.getFormattedMessage() + System.lineSeparator())
				.collect(Collectors.joining());
	}
}
