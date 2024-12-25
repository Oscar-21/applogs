package org.eclipse.store.demo.applogs.ui.views;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.TextField;
import org.eclipse.store.demo.applogs.AppLogsDemo;
import org.eclipse.store.demo.applogs.data.Book;
import org.eclipse.store.demo.applogs.data.Books;

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

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.function.SerializableFunction;
import com.vaadin.flow.router.Route;

/**
 * View to display and modify {@link Books}.
 *
 */
@Route(value = "books", layout = RootLayout.class)
public class ViewBooks extends ViewEntity<Book>
{
	public ViewBooks()
	{
		super();
	}

	@Override
	protected void createUI()
	{
		this.addGridColumnWithTextFilter   ("title"    , Book::title    );
		this.addGridColumnWithDynamicFilter("author"   , Book::author   );
		this.addGridColumnWithDynamicFilter("genre"    , Book::genre    );
		this.addGridColumnWithDynamicFilter("publisher", Book::publisher);
		this.addGridColumnWithDynamicFilter("language" , Book::language );
		this.addGridColumnWithTextFilter   ("isbn13"   , Book::isbn13   );

		final Button showInventoryButton = new Button(
			this.getTranslation("showInventory"),
			VaadinIcon.STOCK.create(),
			event -> this.showInventory(this.getSelectedEntity())
		);
		showInventoryButton.setEnabled(false);
		this.grid.addSelectionListener(event -> {
			final boolean b = event.getFirstSelectedItem().isPresent();
			showInventoryButton.setEnabled(b);
		});

		final Button createBookButton = new Button(
			this.getTranslation("createBook"),
			VaadinIcon.PLUS_CIRCLE.create(),
			event -> this.openCreateBookDialog()
		);

		// Create a TextField for search input
		TextField searchField = new TextField("Search by title");
		searchField.setPlaceholder("Enter book title...");
		// Create a Button to trigger the search
		Button searchButton = new Button("Search");
		// Add a click listener to the button
		searchButton.addClickListener(event -> {
			String searchText = searchField.getValue();
			List<Book> results = AppLogsDemo.getInstance().data().books().searchByTitle(searchText);
			if (results.isEmpty()) {
				Notification.show("No books found with title: " + searchText);
			} else {
				Notification.show(results.size() + " book(s) found with title: " + searchText);
				// You can also display the results in a grid or other component
			}
		});
		this.add(new HorizontalLayout(searchField, searchButton));
		this.add(new HorizontalLayout(showInventoryButton, createBookButton));
	}

	private void showInventory(final Book book)
	{
		this.getUI().get().navigate(ViewInventory.class).get().filterBy(book);
	}

	private void openCreateBookDialog()
	{
		DialogBookCreate.open(book ->
		{
			AppLogsDemo.getInstance().data().books().add(book);
			this.listEntities();
		});
	}



	@Override
	public <R> R compute(final SerializableFunction<Stream<Book>, R> function) {
		return AppLogsDemo.getInstance().data().books().compute(function);
	}
}
