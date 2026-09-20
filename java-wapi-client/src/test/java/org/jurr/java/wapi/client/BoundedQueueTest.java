package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiType;
import org.jurr.java.wapi.WapiValue;
import org.junit.jupiter.api.Test;

class BoundedQueueTest
{
	@Test
	void keepsTheNewestEventsWhenFull()
	{
		final var queue = new BoundedQueue<WapiEvent>(3);
		final var offered = new WapiEvent[5];
		for (var i = 0; i < offered.length; i++)
		{
			offered[i] = new WapiEvent(new WapiToken("ch.1.fdr"), WapiType.I32, WapiValue.ofInt(i));
			queue.offer(offered[i]);
		}

		assertEquals(List.of(offered).subList(2, 5), queue.drain(5));
	}

	@Test
	void offerAllAlsoKeepsTheNewest()
	{
		final var queue = new BoundedQueue<WapiEvent>(2);
		final var first = new WapiEvent(new WapiToken("ch.1.fdr"), WapiType.I32, WapiValue.ofInt(1));
		final var second = new WapiEvent(new WapiToken("ch.1.fdr"), WapiType.I32, WapiValue.ofInt(2));
		final var third = new WapiEvent(new WapiToken("ch.1.fdr"), WapiType.I32, WapiValue.ofInt(3));

		queue.offerAll(List.of(first, second, third));

		assertEquals(List.of(second, third), queue.drain(3));
	}
}
