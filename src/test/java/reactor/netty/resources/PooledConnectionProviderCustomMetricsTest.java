/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.resources;

import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PooledConnectionProviderCustomMetricsTest {

	@Test
	public void customRegistrarIsUsed() {
		AtomicBoolean used = new AtomicBoolean();
		ConnectionProvider pool = ConnectionProvider.builder("test")
				.metrics(true, () -> (a, b, c, d) -> used.set(true))
				.build();

		Bootstrap bootstrap = new Bootstrap().remoteAddress("localhost", 0)
			.channelFactory(NioSocketChannel::new)
			.group(new NioEventLoopGroup(2));


		try {
			pool.acquire(bootstrap).block();
		}
		catch (Exception ignored) {
		}

		assertTrue(used.get());

	}

	@Test
	public void customRegistrarSupplierNotInvokedWhenMetricsDisabled() {
		AtomicBoolean used = new AtomicBoolean();
		ConnectionProvider pool = ConnectionProvider.builder("test")
				.metrics(false, () -> {used.set(true); return null;})
				.build();

		Bootstrap bootstrap = new Bootstrap().remoteAddress("localhost", 0)
				.channelFactory(NioSocketChannel::new)
				.group(new NioEventLoopGroup(2));


		try {
			pool.acquire(bootstrap).block();
		}
		catch (Exception ignored) {
		}

		assertFalse(used.get());

	}
}
