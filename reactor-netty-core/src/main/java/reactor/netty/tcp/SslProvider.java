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

package reactor.netty.tcp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import reactor.core.Exceptions;
import reactor.netty.NettyPipeline;
import reactor.netty.ReactorNetty;
import reactor.netty.channel.ChannelMetricsHandler;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import static reactor.netty.Metrics.ERROR;
import static reactor.netty.Metrics.SUCCESS;
import static reactor.netty.ReactorNetty.format;

/**
 * SSL Provider
 *
 * @author Violeta Georgieva
 */
public final class SslProvider {

	/**
	 * Creates a builder for {@link SslProvider SslProvider}
	 *
	 * @return a new SslProvider builder
	 */
	public static SslProvider.SslContextSpec builder() {
		return new SslProvider.Build();
	}

	/**
	 * Creates a new {@link SslProvider SslProvider} with a prepending handler
	 * configurator callback to inject default settings to an existing provider
	 * configuration.
	 *
	 * @return a new SslProvider
	 */
	public static SslProvider addHandlerConfigurator(
			SslProvider provider, Consumer<? super SslHandler> handlerConfigurator) {
		Objects.requireNonNull(provider, "provider");
		Objects.requireNonNull(handlerConfigurator, "handlerConfigurator");
		return new SslProvider(provider, handlerConfigurator);
	}

	public static SslProvider updateDefaultConfiguration(SslProvider provider, DefaultConfigurationType type) {
		Objects.requireNonNull(provider, "provider");
		Objects.requireNonNull(type, "type");
		return new SslProvider(provider, type);
	}

	/**
	 * Return the default client ssl provider
	 *
	 * @return default client ssl provider
	 */
	public static SslProvider defaultClientProvider() {
		return TcpClientSecure.DEFAULT_SSL_PROVIDER;
	}

	public interface Builder {

		/**
		 * Set a configurator callback to mutate any property from the provided
		 * {@link SslHandler}
		 *
		 * @param handlerConfigurator A callback given the generated {@link SslHandler}
		 *
		 * @return {@literal this}
		 */
		Builder handlerConfigurator(Consumer<? super SslHandler> handlerConfigurator);

		/**
		 * Set the options to use for configuring SSL handshake timeout. Default to 10000 ms.
		 *
		 * @param handshakeTimeout The timeout {@link Duration}
		 *
		 * @return {@literal this}
		 */
		Builder handshakeTimeout(Duration handshakeTimeout);

		/**
		 * Set the options to use for configuring SSL handshake timeout. Default to 10000 ms.
		 *
		 * @param handshakeTimeoutMillis The timeout in milliseconds
		 *
		 * @return {@literal this}
		 */
		Builder handshakeTimeoutMillis(long handshakeTimeoutMillis);

		/**
		 * Set the options to use for configuring SSL close_notify flush timeout. Default to 3000 ms.
		 *
		 * @param closeNotifyFlushTimeout The timeout {@link Duration}
		 *
		 * @return {@literal this}
		 */
		Builder closeNotifyFlushTimeout(Duration closeNotifyFlushTimeout);

		/**
		 * Set the options to use for configuring SSL close_notify flush timeout. Default to 3000 ms.
		 *
		 * @param closeNotifyFlushTimeoutMillis The timeout in milliseconds
		 *
		 * @return {@literal this}
		 */
		Builder closeNotifyFlushTimeoutMillis(long closeNotifyFlushTimeoutMillis);

		/**
		 * Set the options to use for configuring SSL close_notify read timeout. Default to 0 ms.
		 *
		 * @param closeNotifyReadTimeout The timeout {@link Duration}
		 *
		 * @return {@literal this}
		 */
		Builder closeNotifyReadTimeout(Duration closeNotifyReadTimeout);

		/**
		 * Set the options to use for configuring SSL close_notify read timeout. Default to 0 ms.
		 *
		 * @param closeNotifyReadTimeoutMillis The timeout in milliseconds
		 *
		 * @return {@literal this}
		 */
		Builder closeNotifyReadTimeoutMillis(long closeNotifyReadTimeoutMillis);

		/**
		 * Builds new SslProvider
		 *
		 * @return builds new SslProvider
		 */
		SslProvider build();
	}

	public interface SslContextSpec {

		/**
		 * The SslContext to set when configuring SSL
		 *
		 * @param sslContext The context to set when configuring SSL
		 *
		 * @return {@literal this}
		 */
		Builder sslContext(SslContext sslContext);

		/**
		 * The SslContextBuilder for building a new {@link SslContext}.
		 *
		 * @return {@literal this}
		 */
		DefaultConfigurationSpec sslContext(SslContextBuilder sslCtxBuilder);

	}

	/**
	 * Default configuration that will be applied to the provided
	 * {@link SslContextBuilder}
	 */
	public enum DefaultConfigurationType {
		/**
		 * There will be no default configuration
		 */
		NONE,
		/**
		 * {@link io.netty.handler.ssl.SslProvider} will be set depending on
		 * <code>OpenSsl.isAvailable()</code>
		 */
		TCP,
		/**
		 * {@link io.netty.handler.ssl.SslProvider} will be set depending on
		 * <code>OpenSsl.isAlpnSupported()</code>,
		 * {@link #HTTP2_TLS12_CIPHERS},
		 * ALPN support,
		 * HTTP/1.1 and HTTP/2 support
		 */
		H2
	}

	public interface DefaultConfigurationSpec {

		/**
		 * Default configuration type that will be applied to the provided
		 * {@link SslContextBuilder}
		 *
		 * @param type The default configuration type.
		 * @return {@code this}
		 */
		Builder defaultConfiguration(DefaultConfigurationType type);
	}

	final SslContext                   sslContext;
	final SslContextBuilder            sslContextBuilder;
	final DefaultConfigurationType     type;
	final long                         handshakeTimeoutMillis;
	final long                         closeNotifyFlushTimeoutMillis;
	final long                         closeNotifyReadTimeoutMillis;
	final Consumer<? super SslHandler> handlerConfigurator;
	final int                          builderHashCode;

	SslProvider(SslProvider.Build builder) {
		this.sslContextBuilder = builder.sslCtxBuilder;
		this.type = builder.type;
		if (builder.sslContext == null) {
			if (sslContextBuilder != null) {
				if (type != null) {
					updateDefaultConfiguration();
				}
				try {
					this.sslContext = sslContextBuilder.build();
				}
				catch (SSLException e) {
					throw Exceptions.propagate(e);
				}
			}
			else {
				throw new IllegalArgumentException("Neither SslContextBuilder nor SslContext is specified");
			}
		}
		else {
			this.sslContext = builder.sslContext;
		}
		this.handlerConfigurator = builder.handlerConfigurator;
		this.handshakeTimeoutMillis = builder.handshakeTimeoutMillis;
		this.closeNotifyFlushTimeoutMillis = builder.closeNotifyFlushTimeoutMillis;
		this.closeNotifyReadTimeoutMillis = builder.closeNotifyReadTimeoutMillis;
		this.builderHashCode = builder.hashCode();
	}

	SslProvider(SslProvider from, Consumer<? super SslHandler> handlerConfigurator) {
		this.sslContext = from.sslContext;
		this.sslContextBuilder = from.sslContextBuilder;
		this.type = from.type;
		if (from.handlerConfigurator == null) {
			this.handlerConfigurator = handlerConfigurator;
		}
		else {
			this.handlerConfigurator = h -> {
				handlerConfigurator.accept(h);
				from.handlerConfigurator.accept(h);
			};
		}
		this.handshakeTimeoutMillis = from.handshakeTimeoutMillis;
		this.closeNotifyFlushTimeoutMillis = from.closeNotifyFlushTimeoutMillis;
		this.closeNotifyReadTimeoutMillis = from.closeNotifyReadTimeoutMillis;
		this.builderHashCode = from.builderHashCode;
	}

	SslProvider(SslProvider from, DefaultConfigurationType type) {
		this.sslContextBuilder = from.sslContextBuilder;
		this.type = type;
		if (this.sslContextBuilder != null) {
			updateDefaultConfiguration();
			try {
				this.sslContext = sslContextBuilder.build();
			}
			catch (SSLException e) {
				throw Exceptions.propagate(e);
			}
		}
		else {
			this.sslContext = from.sslContext;
		}
		this.handlerConfigurator = from.handlerConfigurator;
		this.handshakeTimeoutMillis = from.handshakeTimeoutMillis;
		this.closeNotifyFlushTimeoutMillis = from.closeNotifyFlushTimeoutMillis;
		this.closeNotifyReadTimeoutMillis = from.closeNotifyReadTimeoutMillis;
		this.builderHashCode = from.builderHashCode;
	}

	void updateDefaultConfiguration() {
		switch (type) {
			case H2:
				sslContextBuilder.sslProvider(
				                     io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
				                             io.netty.handler.ssl.SslProvider.OPENSSL :
				                             io.netty.handler.ssl.SslProvider.JDK)
				                 .ciphers(HTTP2_TLS12_CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
				                 .applicationProtocolConfig(new ApplicationProtocolConfig(
				                     ApplicationProtocolConfig.Protocol.ALPN,
				                     ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
				                     ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
				                     ApplicationProtocolNames.HTTP_2,
				                     ApplicationProtocolNames.HTTP_1_1));
				break;
			case TCP:
				sslContextBuilder.sslProvider(
				                     OpenSsl.isAvailable() ?
				                             io.netty.handler.ssl.SslProvider.OPENSSL :
				                             io.netty.handler.ssl.SslProvider.JDK)
				                 .ciphers(null, IdentityCipherSuiteFilter.INSTANCE)
				                 .applicationProtocolConfig(null);
				break;
			case NONE:
				break; //no default configuration
		}
	}

	/**
	 * Returns {@code SslContext} instance with configured settings.
	 *
	 * @return {@code SslContext} instance with configured settings.
	 */
	public SslContext getSslContext() {
		return this.sslContext;
	}

	/**
	 * Returns the configured default configuration type.
	 *
	 * @return the configured default configuration type.
	 */
	@Nullable
	public DefaultConfigurationType getDefaultConfigurationType() {
		return this.type;
	}

	public void configure(SslHandler sslHandler) {
		sslHandler.setHandshakeTimeoutMillis(handshakeTimeoutMillis);
		sslHandler.setCloseNotifyFlushTimeoutMillis(closeNotifyFlushTimeoutMillis);
		sslHandler.setCloseNotifyReadTimeoutMillis(closeNotifyReadTimeoutMillis);
		if (handlerConfigurator != null) {
			handlerConfigurator.accept(sslHandler);
		}
	}

	public void addSslHandler(Channel channel, @Nullable SocketAddress remoteAddress, boolean sslDebug) {
		SslHandler sslHandler;

		if (remoteAddress instanceof InetSocketAddress) {
			InetSocketAddress sniInfo = (InetSocketAddress) remoteAddress;
			sslHandler = getSslContext()
					.newHandler(channel.alloc(), sniInfo.getHostString(), sniInfo.getPort());

			if (log.isDebugEnabled()) {
				log.debug(format(channel, "SSL enabled using engine {} and SNI {}"),
						sslHandler.engine().getClass().getSimpleName(), sniInfo);
			}
		}
		else {
			sslHandler = getSslContext().newHandler(channel.alloc());

			if (log.isDebugEnabled()) {
				log.debug(format(channel, "SSL enabled using engine {}"),
						sslHandler.engine().getClass().getSimpleName());
			}
		}

		configure(sslHandler);

		ChannelPipeline pipeline = channel.pipeline();
		if (pipeline.get(NettyPipeline.ProxyHandler) != null) {
			pipeline.addAfter(NettyPipeline.ProxyHandler, NettyPipeline.SslHandler, sslHandler);
		}
		else {
			pipeline.addFirst(NettyPipeline.SslHandler, sslHandler);
		}

		if (pipeline.get(NettyPipeline.LoggingHandler) != null) {
			pipeline.addAfter(NettyPipeline.LoggingHandler, NettyPipeline.SslReader, new SslReadHandler());
			if (sslDebug) {
				pipeline.addBefore(NettyPipeline.SslHandler,
						NettyPipeline.SslLoggingHandler,
						new LoggingHandler("reactor.netty.tcp.ssl"));
			}

		}
		else {
			pipeline.addAfter(NettyPipeline.SslHandler, NettyPipeline.SslReader, new SslReadHandler());
		}
	}

	@Override
	public String toString() {
		return "SslProvider {" +
				"type=" + type +
				", handshakeTimeoutMillis=" + handshakeTimeoutMillis +
				", closeNotifyFlushTimeoutMillis=" + closeNotifyFlushTimeoutMillis +
				", closeNotifyReadTimeoutMillis=" + closeNotifyReadTimeoutMillis +
				'}';
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		SslProvider that = (SslProvider) o;
		return builderHashCode == that.builderHashCode;
	}

	@Override
	public int hashCode() {
		return Objects.hash(builderHashCode);
	}

	static final class Build implements SslContextSpec, DefaultConfigurationSpec, Builder {

		/**
		 * Default SSL handshake timeout (milliseconds), fallback to 10 seconds
		 */
		static final long DEFAULT_SSL_HANDSHAKE_TIMEOUT =
				Long.parseLong(System.getProperty(
						ReactorNetty.SSL_HANDSHAKE_TIMEOUT,
						"10000"));

		SslContextBuilder sslCtxBuilder;
		DefaultConfigurationType type;
		SslContext sslContext;
		Consumer<? super SslHandler> handlerConfigurator;
		long handshakeTimeoutMillis = DEFAULT_SSL_HANDSHAKE_TIMEOUT;
		long closeNotifyFlushTimeoutMillis = 3000L;
		long closeNotifyReadTimeoutMillis;

		// SslContextSpec

		@Override
		public final Builder sslContext(SslContext sslContext) {
			this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
			return this;
		}

		@Override
		public final DefaultConfigurationSpec sslContext(SslContextBuilder sslCtxBuilder) {
			this.sslCtxBuilder = Objects.requireNonNull(sslCtxBuilder, "sslCtxBuilder");
			return this;
		}

		//DefaultConfigurationSpec

		@Override
		public final Builder defaultConfiguration(DefaultConfigurationType type) {
			this.type = Objects.requireNonNull(type, "type");
			return this;
		}

		// Builder

		@Override
		public final Builder handshakeTimeout(Duration handshakeTimeout) {
			Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
			return handshakeTimeoutMillis(handshakeTimeout.toMillis());
		}

		@Override
		public final Builder handlerConfigurator(Consumer<? super SslHandler> handlerConfigurator) {
			Objects.requireNonNull(handlerConfigurator, "handshakeTimeout");
			this.handlerConfigurator = handlerConfigurator;
			return this;
		}

		@Override
		public final Builder handshakeTimeoutMillis(long handshakeTimeoutMillis) {
			if (handshakeTimeoutMillis < 0L) {
				throw new IllegalArgumentException("ssl handshake timeout must be positive"
						+ " was: " + handshakeTimeoutMillis);
			}
			this.handshakeTimeoutMillis = handshakeTimeoutMillis;
			return this;
		}

		@Override
		public final Builder closeNotifyFlushTimeout(Duration closeNotifyFlushTimeout) {
			Objects.requireNonNull(closeNotifyFlushTimeout, "closeNotifyFlushTimeout");
			return closeNotifyFlushTimeoutMillis(closeNotifyFlushTimeout.toMillis());
		}

		@Override
		public final Builder closeNotifyFlushTimeoutMillis(long closeNotifyFlushTimeoutMillis) {
			if (closeNotifyFlushTimeoutMillis < 0L) {
				throw new IllegalArgumentException("ssl close_notify flush timeout must be positive,"
						+ " was: " + closeNotifyFlushTimeoutMillis);
			}
			this.closeNotifyFlushTimeoutMillis = closeNotifyFlushTimeoutMillis;
			return this;
		}

		@Override
		public final Builder closeNotifyReadTimeout(Duration closeNotifyReadTimeout) {
			Objects.requireNonNull(closeNotifyReadTimeout, "closeNotifyReadTimeout");
			return closeNotifyReadTimeoutMillis(closeNotifyReadTimeout.toMillis());
		}

		@Override
		public final Builder closeNotifyReadTimeoutMillis(long closeNotifyReadTimeoutMillis) {
			if (closeNotifyReadTimeoutMillis < 0L) {
				throw new IllegalArgumentException("ssl close_notify read timeout must be positive,"
						+ " was: " + closeNotifyReadTimeoutMillis);
			}
			this.closeNotifyReadTimeoutMillis = closeNotifyReadTimeoutMillis;
			return this;
		}

		@Override
		public SslProvider build() {
			return new SslProvider(this);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			Build build = (Build) o;
			return handshakeTimeoutMillis == build.handshakeTimeoutMillis &&
					closeNotifyFlushTimeoutMillis == build.closeNotifyFlushTimeoutMillis &&
					closeNotifyReadTimeoutMillis == build.closeNotifyReadTimeoutMillis &&
					Objects.equals(sslCtxBuilder, build.sslCtxBuilder) &&
					type == build.type &&
					Objects.equals(sslContext, build.sslContext) &&
					Objects.equals(handlerConfigurator, build.handlerConfigurator);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sslCtxBuilder, type, sslContext, handlerConfigurator,
					handshakeTimeoutMillis, closeNotifyFlushTimeoutMillis, closeNotifyReadTimeoutMillis);
		}
	}

	static final class SslReadHandler extends ChannelInboundHandlerAdapter {

		boolean handshakeDone;

		ChannelMetricsRecorder recorder;

		long tlsHandshakeTimeStart;

		@Override
		public void channelRegistered(ChannelHandlerContext ctx) {
			ChannelHandler handler = ctx.pipeline().get(NettyPipeline.ChannelMetricsHandler);
			if (handler != null) {
				recorder = ((ChannelMetricsHandler) handler).recorder();
				tlsHandshakeTimeStart = System.nanoTime();
			}

			ctx.fireChannelRegistered();
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) {
			ctx.read(); //consume handshake
		}

		@Override
		public void channelReadComplete(ChannelHandlerContext ctx) {
			if (!handshakeDone) {
				ctx.read(); /* continue consuming. */
			}
			ctx.fireChannelReadComplete();
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
			if (evt instanceof SslHandshakeCompletionEvent) {
				handshakeDone = true;
				if (ctx.pipeline()
				       .context(this) != null) {
					ctx.pipeline()
					   .remove(this);
				}
				SslHandshakeCompletionEvent handshake = (SslHandshakeCompletionEvent) evt;
				if (handshake.isSuccess()) {
					if (recorder != null) {
						recorder.recordTlsHandshakeTime(
								ctx.channel().remoteAddress(),
								Duration.ofNanos(System.nanoTime() - tlsHandshakeTimeStart),
								SUCCESS);
					}
					ctx.fireChannelActive();
				}
				else {
					if (recorder != null) {
						recorder.recordTlsHandshakeTime(
								ctx.channel().remoteAddress(),
								Duration.ofNanos(System.nanoTime() - tlsHandshakeTimeStart),
								ERROR);
					}
					ctx.fireExceptionCaught(handshake.cause());
				}
			}
			ctx.fireUserEventTriggered(evt);
		}

	}

	static final Logger log = Loggers.getLogger(SslProvider.class);

	/**
	 * <a href="https://wiki.mozilla.org/Security/Server_Side_TLS#Modern_compatibility">Mozilla Modern Cipher
	 * Suites</a> minus the following cipher suites that are black listed by the
	 * <a href="https://tools.ietf.org/html/rfc7540#appendix-A">HTTP/2 RFC</a>.
	 * Copied from io.netty.handler.codec.http2.Http2SecurityUtil
	 */
	static final List<String> HTTP2_TLS12_CIPHERS =
			Collections.unmodifiableList(Arrays.asList(
					/* openssl = ECDHE-ECDSA-AES256-GCM-SHA384 */
					"TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
					/* openssl = ECDHE-RSA-AES256-GCM-SHA384 */
					"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
					/* openssl = ECDHE-ECDSA-CHACHA20-POLY1305 */
					"TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
					/* openssl = ECDHE-RSA-CHACHA20-POLY1305 */
					"TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
					/* openssl = ECDHE-ECDSA-AES128-GCM-SHA256 */
					"TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",

					/* REQUIRED BY HTTP/2 SPEC */
					/* openssl = ECDHE-RSA-AES128-GCM-SHA256 */
					"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
					/* REQUIRED BY HTTP/2 SPEC */));
}



