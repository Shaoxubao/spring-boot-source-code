/*
 * Copyright 2012-2018 the original author or authors.
 *
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
 */

package org.springframework.boot.context.event;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ErrorHandler;

/**
 * {@link SpringApplicationRunListener} to publish {@link SpringApplicationEvent}s.
 * <p>
 * Uses an internal {@link ApplicationEventMulticaster} for the events that are fired
 * before the context is actually refreshed.
 *
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 * @author Artsiom Yudovin
 * EventPublishingRunListener是对SpringApplicationRunListener接口的唯一内建实现
 * EventPublishingRunListener使用内置的SimpleApplicationEventMulticaster来广播在上下文刷新之前出发的事件
 * EventPublishingRunListener针对不同的事件提供了不同的处理方法，但它们的处理流程基本相同
 *
 * 整个事件的流程：
 * 1、程序启动到某个步骤后，调用EventPublishingRunListener的某个方法。
 * 2、EventPublishingRunListener的具体方法将application参数和args参数封装在对应的事件中，这里的事件均为
 * 	  SpringApplicationEvent的实现类。
 * 3、通过成员变量initialMulticaster的multicastEvent方法对事件进行广播，或通过该方法的ConfigurableApplicationContext
 * 	  参数的publishEvent方法来对事件发布。(ConfigurableApplicationContext继承ApplicationContext，
 * 	  而ApplicationContext又继承ApplicationEventPublisher，所以继承了ApplicationEventPublisher的publishEvent方法)
 * 4、对应的ApplicationListener被触发，执行相应的业务逻辑。
 */
public class EventPublishingRunListener implements SpringApplicationRunListener, Ordered {

	private final SpringApplication application;

	private final String[] args;

	// 事件广播器
	private final SimpleApplicationEventMulticaster initialMulticaster;

	public EventPublishingRunListener(SpringApplication application, String[] args) {
		this.application = application;
		this.args = args;
		this.initialMulticaster = new SimpleApplicationEventMulticaster();
		// 遍历所有ApplicationListener实例，将它们与SimpleApplicationEventMulticaster进行关联，
		// 方便SimpleApplicationEventMulticaster后续将事件传递给所有的监听器
		for (ApplicationListener<?> listener : application.getListeners()) {
			this.initialMulticaster.addApplicationListener(listener);
		}
	}

	@Override
	public int getOrder() {
		return 0;
	}

	@Override
	public void starting() {
		this.initialMulticaster.multicastEvent(
				new ApplicationStartingEvent(this.application, this.args));
	}

	@Override
	public void environmentPrepared(ConfigurableEnvironment environment) {
		this.initialMulticaster.multicastEvent(new ApplicationEnvironmentPreparedEvent(
				this.application, this.args, environment));
	}

	@Override
	public void contextPrepared(ConfigurableApplicationContext context) {
		this.initialMulticaster.multicastEvent(new ApplicationContextInitializedEvent(
				this.application, this.args, context));
	}

	// contextLoaded方法在发布事件之前做了两件事：第一，遍历application的所有监听器实现类，
	// 如果该实现类还实现了ApplicationContextAware接口，
	// 则将上下文信息设置到该监听器内；第二，将application中的监听器实现类全部添加到上下文中。
	// 最后一步才是调用事件广播。也正是这个方法形成了不同事件广播形式的分水岭，
	// 在此方法之前执行的事件广播都是通过multicastEvent来进行的，而该方法之后的方法则均采用publishEvent来执行。
	// 这是因为只有到了contextLoaded方法之后，上下文才算初始化完成，才可通过它的publishEvent方法来进行事件的发布。
	@Override
	public void contextLoaded(ConfigurableApplicationContext context) {
		for (ApplicationListener<?> listener : this.application.getListeners()) {
			if (listener instanceof ApplicationContextAware) {
				((ApplicationContextAware) listener).setApplicationContext(context);
			}
			context.addApplicationListener(listener);
		}
		this.initialMulticaster.multicastEvent(
				new ApplicationPreparedEvent(this.application, this.args, context));
	}

	@Override
	public void started(ConfigurableApplicationContext context) {
		context.publishEvent(
				new ApplicationStartedEvent(this.application, this.args, context));
	}

	@Override
	public void running(ConfigurableApplicationContext context) {
		context.publishEvent(
				new ApplicationReadyEvent(this.application, this.args, context));
	}

	@Override
	public void failed(ConfigurableApplicationContext context, Throwable exception) {
		ApplicationFailedEvent event = new ApplicationFailedEvent(this.application,
				this.args, context, exception);
		if (context != null && context.isActive()) {
			// Listeners have been registered to the application context so we should
			// use it at this point if we can
			context.publishEvent(event);
		}
		else {
			// An inactive context may not have a multicaster so we use our multicaster to
			// call all of the context's listeners instead
			if (context instanceof AbstractApplicationContext) {
				for (ApplicationListener<?> listener : ((AbstractApplicationContext) context)
						.getApplicationListeners()) {
					this.initialMulticaster.addApplicationListener(listener);
				}
			}
			this.initialMulticaster.setErrorHandler(new LoggingErrorHandler());
			this.initialMulticaster.multicastEvent(event);
		}
	}

	private static class LoggingErrorHandler implements ErrorHandler {

		private static Log logger = LogFactory.getLog(EventPublishingRunListener.class);

		@Override
		public void handleError(Throwable throwable) {
			logger.warn("Error calling ApplicationEventListener", throwable);
		}

	}

}
