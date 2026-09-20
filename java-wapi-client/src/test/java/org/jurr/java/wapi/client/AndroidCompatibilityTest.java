package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.jurr.java.android.classcompatibilitychecker.AndroidClassCompatibilityCheckerBuilder;

class AndroidCompatibilityTest
{
	@Test
	void test() throws IOException
	{
		final var androidChecker = new AndroidClassCompatibilityCheckerBuilder(34).withTempDirectory(Paths.get("target/")).build();
		final var testResults = androidChecker.testPackage("org.jurr.java.wapi.client");
		assertTrue(testResults.isSuccessful(), "Android compatibility test failed: " + testResults.toErrorDescription());
	}
}
