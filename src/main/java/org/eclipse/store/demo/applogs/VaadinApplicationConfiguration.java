package org.eclipse.store.demo.applogs;

/*-
 * #%L
 * AppLogs Demo
 * %%
 * Copyright (C) 2023 - 2024 MicroStream Software
 * %%
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * #L%
 */

import org.eclipse.store.demo.applogs.data.RandomDataAmount;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VaadinApplicationConfiguration
{
	public VaadinApplicationConfiguration()
	{
		super();
	}

	/**
	 * Manages the creation and disposal of the {@link AppLogsDemo} singleton.
	 */
	@Bean(destroyMethod = "shutdown")
	public AppLogsDemo getBookStoreDemo()
	{
		final AppLogsDemo demo = new AppLogsDemo(RandomDataAmount.Medium());
		demo.storageManager(); // eager init
		return demo;
	}
}
