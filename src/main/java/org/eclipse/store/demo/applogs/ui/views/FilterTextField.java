package org.eclipse.store.demo.applogs.ui.views;

/*-
 * #%L
 * EclipseStore BookStore Demo
 * %%
 * Copyright (C) 2023 MicroStream Software
 * %%
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * #L%
 */

import static org.eclipse.serializer.util.X.notNull;

import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.function.SerializableFunction;
import com.vaadin.flow.function.SerializablePredicate;

/**
 * Filter {@link TextField} for arbitrary entities.
 *
 * @param <E> the entity type
 */
public class FilterTextField<E> extends TextField implements FilterField<E, String>
{
	private final SerializableFunction<String, SerializablePredicate<E>> filterFactory;

	public FilterTextField(
		final SerializableFunction<String, SerializablePredicate<E>> filterFactory
	)
	{
		super();

		this.filterFactory = notNull(filterFactory);

		this.setPlaceholder(this.getTranslation("filter"));
		this.setClearButtonVisible(true);
		this.setValueChangeMode(ValueChangeMode.LAZY);
	}

	@Override
	public SerializablePredicate<E> filter(final SerializablePredicate<E> filter)
	{
		String value = this.getValue();
		return value != null && (value = value.trim()).length() > 0
			? filter.and(this.filterFactory.apply(value))
			: filter
		;
	}

	@Override
	public void updateOptions() {
		// NOOP
	}
}
