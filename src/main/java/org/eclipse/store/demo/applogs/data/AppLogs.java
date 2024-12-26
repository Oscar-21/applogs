
package org.eclipse.store.demo.applogs.data;

/*-
 * #%L
 * EclipseStore BookStore Demo
 * %%
 * Copyright (C) 2023 MicroStream Softwa
 * %%
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * #L%
 */

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingInt;
import static java.util.stream.Collectors.toList;
import static org.eclipse.store.demo.applogs.util.CollectionUtils.ensureParallelStream;
import static org.eclipse.store.demo.applogs.util.CollectionUtils.maxKey;
import static org.eclipse.store.demo.applogs.util.CollectionUtils.summingMonetaryAmount;
import static org.eclipse.store.demo.applogs.util.LazyUtils.clearIfStored;
import static org.javamoney.moneta.function.MonetaryFunctions.summarizingMonetary;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.money.MonetaryAmount;

import org.eclipse.serializer.persistence.types.PersistenceStoring;
import org.eclipse.serializer.reference.Lazy;
import org.eclipse.store.demo.applogs.AppLogsDemo;
import org.eclipse.store.demo.applogs.util.concurrent.ReadWriteLocked;
import org.eclipse.store.demo.applogs.util.concurrent.ReadWriteLockedStriped;
import org.eclipse.store.storage.embedded.types.EmbeddedStorageManager;

import com.google.common.collect.Range;

/**
 * All purchases made by all customers in all stores.
 * <p>
 * This type is used to read and write the {@link Purchase}s and statistics thereof.
 * <p>
 * All operations on this type are thread safe.
 *
 * @see Data#purchases()
 * @see ReadWriteLocked
 */
public class AppLogs extends ReadWriteLockedStriped
{
	/**
	 * This class hold all purchases made in a specific year.
	 * <p>
	 * Note that this class doesn't need to handle concurrency in any way,
	 * since it is only used by the Purchases implementation which handles thread safety.
	 */
	private static class DailyLogs
	{
		/*
		 * Multiple maps holding references to the purchases, for a faster lookup.
		 */
		final Map<Level,    Lazy<List<AppLog>>>   levelToAppLogs      = new HashMap<>();

		DailyLogs()
		{
			super();
		}

		/**
		 * Adds a purchase to all collections used by this class.
		 *
		 * @param purchase the purchase to add
		 */
		DailyLogs add(
			final AppLog             appLog,
			final PersistenceStoring persister
		)
		{
			final List<Object> changedObjects = new ArrayList<>();
			addToMap(
					this.levelToAppLogs,
					appLog.level(),
					appLog,
					changedObjects
			);
			if(persister != null && changedObjects.size() > 0)
			{
				persister.storeAll(changedObjects);
			}
			return this;
		}

		/**
		 * Adds a purchase to a map with a list as values.
		 * If no list is present for the given key, it will be created.
		 *
		 * @param <K> the key type
		 * @param map the collection
		 * @param key the key
		 * @param purchase the purchase to add
		 */
		private static <K> void addToMap(
			final Map<K, Lazy<List<AppLog>>> map,
			final K key,
			final AppLog appLog,
			final List<Object> changedObjects
		)
		{
			Lazy<List<AppLog>> lazy = map.get(key);
			if(lazy == null)
			{
				final ArrayList<AppLog> list = new ArrayList<>();
				list.add(appLog);
				lazy = Lazy.Reference(list);
				map.put(key, lazy);
				changedObjects.add(map);
			}
			else
			{
				final List<AppLog> list = lazy.get();
				list.add(appLog);
				changedObjects.add(list);
			}
		}

		/**
		 * Clears all {@link Lazy} references used by this type
		 */
		void clear()
		{
			clearMap(this.levelToAppLogs);
		}

		/**
		 * Clears all {@link Lazy} references in the given map.
		 *
		 * @param <K> the key type
		 * @param map the map to clear
		 */
		private static <K> void clearMap(
			final Map<K, Lazy<List<AppLog>>> map
		)
		{
			map.values().forEach(lazy ->
				clearIfStored(lazy).ifPresent(List::clear)
			);
		}

		/**
		 * @param shop the shop to filter by
		 * @return parallel stream with purchases made in a specific shop
		 */
		Stream<AppLog> byLevel(
			final Level level
		)
		{
			return ensureParallelStream(
				Lazy.get(this.levelToAppLogs.get(level))
			);
		}

		/**
		 * @param shopSelector the predicate to filter by
		 * @return parallel stream with purchases made in specific shops
		 */
		Stream<AppLog> byLevels(
			final Predicate<Level> levelSelector
		)
		{
			return this.levelToAppLogs.entrySet().parallelStream()
				.filter(e -> levelSelector.test(e.getKey()))
				.flatMap(e -> ensureParallelStream(Lazy.get(e.getValue())));
		}
	}
	/**
	 * Map with {@link DailyLogs}, indexed by the year, of course.
	 */
	private final Map<LocalDate, Lazy<DailyLogs>> dailyLogs = new ConcurrentHashMap<>();

	public AppLogs()
	{
		super();
	}
	
	/**
	 * Adds a new purchase and stores it with the {@link AppLogsDemo}'s {@link EmbeddedStorageManager}.
	 * <p>
	 * This is a synonym for:<pre>this.add(purchase, AppLogsDemo.getInstance().storageManager())</pre>
	 *
	 * @param purchase the new purchase
	 */
	public void add(final AppLog appLog)
	{
		this.add(appLog, AppLogsDemo.getInstance().storageManager());
	}

	/**
	 * Adds a new purchase and stores it with the given persister.
	 *
	 * @param purchase the new purchase
	 * @param persister the persister to store it with
	 * @see #add(Purchase)
	 */
	public void add(
		final AppLog             appLog  ,
		final PersistenceStoring persister
	)
	{
		final LocalDate dayOfYear = appLog.timestamp().toLocalDate();
		this.write(dayOfYear, () ->
		{
			final Lazy<DailyLogs> lazy = this.dailyLogs.get(dayOfYear);
			if(lazy != null)
			{
				lazy.get().add(appLog, persister);
			}
			else
			{
				this.write(0, () -> {
					this.dailyLogs.put(
						dayOfYear,
						Lazy.Reference(
							new DailyLogs().add(appLog, null)
						)
					);
					persister.store(this.dailyLogs);
				});
			}
		});
	}

	/**
	 * Gets the Oldest and newest day in which app logs were made.
	 *
	 * @return all years with revenue
	 */
	public Range<LocalDate> days()
	{
		return this.read(0, () -> {
			final List<LocalDate> summary = this.dailyLogs.keySet().stream()
					.sorted((currentDate, nextDate) -> currentDate.compareTo(nextDate)).toList();
			LocalDate oldest = !summary.isEmpty() ? summary.get(0) : null;
			LocalDate newest = !summary.isEmpty() && summary.size() >= 1 ? summary.get(summary.size() - 1) : null;
			return Range.closed(oldest, newest);
		});
	}

	/**
	 * Clears all {@link Lazy} references regarding all purchases.
	 * This frees the used memory but you do not lose the persisted data. It is loaded again on demand.
	 *
	 * @see #clear(int)
	 */
	public void clear()
	{
		final List<LocalDate> days = this.read(0, () ->
			new ArrayList<>(this.dailyLogs.keySet())
		);
		days.forEach(this::clear);
	}

	/**
	 * Clears all {@link Lazy} references regarding purchases of a specific year.
	 * This frees the used memory but you do not lose the persisted data. It is loaded again on demand.
	 *
	 * @param year the year to clear
	 * @see #clear()
	 */
	public void clear(
		final LocalDate day
	)
	{
		this.write(day, () ->
			clearIfStored(this.dailyLogs.get(day))
				.ifPresent(DailyLogs::clear)
		);
	}

	/**
	 * Executes a function with a pre-filtered {@link Stream} of {@link Purchase}s and returns the computed value.
	 *
	 * @param <T> the return type
	 * @param year year to filter by
	 * @param streamFunction computing function
	 * @return the computed result
	 */
	public <T> T computeByDay(
		final LocalDate                      day          ,
		final Function<Stream<AppLog>, T> streamFunction
	)
	{
		return this.read(day, () ->
		{
			final DailyLogs dailyLogs = Lazy.get(this.dailyLogs.get(day));
			return streamFunction.apply(
				dailyLogs == null
					? Stream.empty()
					: dailyLogs.levelToAppLogs.values().parallelStream()
						.map(l -> l.get())
						.flatMap(List::stream)
			);
		});
	}

	/**
	 * Executes a function with a pre-filtered {@link Stream} of {@link Purchase}s and returns the computed value.
	 *
	 * @param <T> the return type
	 * @param shop shop to filter by
	 * @param year year to filter by
	 * @param streamFunction computing function
	 * @return the computed result
	 */
	public <T> T computeByLevelAndDay(
		final Level                         level        ,
		final LocalDate                     day          ,
		final Function<Stream<AppLog>, T> streamFunction
	)
	{
		return this.read(day, () ->
		{
			final DailyLogs dailyLogs = Lazy.get(this.dailyLogs.get(day));
			return streamFunction.apply(
				dailyLogs == null
					? Stream.empty()
					: dailyLogs.byLevel(level)
			);
		});
	}

	/**
	 * Executes a function with a pre-filtered {@link Stream} of {@link Purchase}s and returns the computed value.
	 *
	 * @param <T> the return type
	 * @param shopSelector predicate for shops to filter by
	 * @param year year to filter by
	 * @param streamFunction computing function
	 * @return the computed result
	 */
	public <T> T computeByLevelsAndDay(
		final Predicate<Level>              levelSelector  ,
		final LocalDate                     day          ,
		final Function<Stream<AppLog>, T>   streamFunction
	)
	{
		return this.read(day, () ->
		{
			final DailyLogs dailyLogs = Lazy.get(this.dailyLogs.get(day));
			return streamFunction.apply(
				dailyLogs == null
					? Stream.empty()
					: dailyLogs.byLevel(levelSelector)
			);
		});
	}


	/**
	 * Computes the best selling books for a specific year.
	 *
	 * @param year the year to filter by
	 * @return list of best selling books
	 */
	public List<BookSales> bestSellerList(final int year)
	{
		return this.computeByYear(
			year,
			this::bestSellerList
		);
	}

	/**
	 * Computes the best selling books for a specific year and country.
	 *
	 * @param year the year to filter by
	 * @param country the country to filter by
	 * @return list of best selling books
	 */
	public List<BookSales> bestSellerList(
		final int     year   ,
		final Country country
	)
	{
		return this.computeByShopsAndYear(
			shopInCountryPredicate(country),
			year,
			this::bestSellerList
		);
	}
	
	private List<BookSales> bestSellerList(final Stream<Purchase> purchases)
	{
		return purchases
			.flatMap(Purchase::items)
			.collect(
				groupingBy(
					PurchaseItem::book,
					summingInt(PurchaseItem::amount)
				)
			)
			.entrySet()
			.stream()
			.map(e -> new BookSales(e.getKey(), e.getValue()))
			.sorted()
			.collect(toList());
	}

	/**
	 * Counts all purchases which were made by customers in foreign countries.
	 *
	 * @param year the year to filter by
	 * @return the amount of computed purchases
	 */
	public long countPurchasesOfForeigners(final int year)
	{
		return this.computePurchasesOfForeigners(
			year,
			purchases -> purchases.count()
		);
	}

	/**
	 * Computes all purchases which were made by customers in foreign cities.
	 *
	 * @param year the year to filter by
	 * @return a list of purchases
	 */
	public List<Purchase> purchasesOfForeigners(final int year)
	{
		return this.computePurchasesOfForeigners(
			year,
			purchases -> purchases.collect(toList())
		);
	}
	
	private <T> T computePurchasesOfForeigners(
		final int                            year          ,
		final Function <Stream<Purchase>, T> streamFunction
	)
	{
		return this.computeByYear(
			year,
			purchases -> streamFunction.apply(
				purchases.filter(
					purchaseOfForeignerPredicate()
				)
			)
		);
	}

	/**
	 * Counts all purchases which were made by customers in foreign cities.
	 *
	 * @param year the year to filter by
	 * @param country the country to filter by
	 * @return the amount of computed purchases
	 */
	public long countPurchasesOfForeigners(
		final int     year   ,
		final Country country
	)
	{
		return this.computePurchasesOfForeigners(
			year,
			country,
			purchases -> purchases.count()
		);
	}

	/**
	 * Computes all purchases which were made by customers in foreign cities.
	 *
	 * @param year the year to filter by
	 * @param country the country to filter by
	 * @return a list of purchases
	 */
	public List<Purchase> purchasesOfForeigners(
		final int     year   ,
		final Country country
	)
	{
		return this.computePurchasesOfForeigners(
			year,
			country,
			purchases -> purchases.collect(toList())
		);
	}

	private <T> T computePurchasesOfForeigners(
		final int                            year          ,
		final Country                        country       ,
		final Function <Stream<Purchase>, T> streamFunction
	)
	{
		return this.computeByShopsAndYear(
			shopInCountryPredicate(country),
			year,
			purchases -> streamFunction.apply(
				purchases.filter(
					purchaseOfForeignerPredicate()
				)
			)
		);
	}
	
	private static Predicate<Shop> shopInCountryPredicate(final Country country)
	{
		return shop -> shop.address().city().state().country() == country;
	}

	private static Predicate<? super Purchase> purchaseOfForeignerPredicate()
	{
		return p -> p.customer().address().city() != p.shop().address().city();
	}

	/**
	 * Computes the complete revenue of a specific shop in a whole year.
	 *
	 * @param shop the shop to filter by
	 * @param year the year to filter by
	 * @return complete revenue
	 */
	public MonetaryAmount revenueOfShopInYear(
		final Shop shop,
		final int  year
	)
	{
		return this.computeByShopAndYear(
			shop,
			year,
			purchases -> purchases
				.map(Purchase::total)
				.collect(summarizingMonetary(AppLogsDemo.CURRENCY_UNIT))
				.getSum()
		);
	}

	/**
	 * Computes the worldwide best performing employee in a specific year.
	 *
	 * @param year the year to filter by
	 * @return the employee which made the most revenue
	 */
	public Employee employeeOfTheYear(final int year)
	{
		return this.computeByYear(
			year,
			bestPerformingEmployeeFunction()
		);
	}

	/**
	 * Computes the best performing employee in a specific year.
	 *
	 * @param year the year to filter by
	 * @param country the country to filter by
	 * @return the employee which made the most revenue
	 */
	public Employee employeeOfTheYear(
		final int     year   ,
		final Country country
	)
	{
		return this.computeByShopsAndYear(
			shopInCountryPredicate(country) ,
			year,
			bestPerformingEmployeeFunction()
		);
	}

	private static Function<Stream<Purchase>, Employee> bestPerformingEmployeeFunction()
	{
		return purchases -> maxKey(
			purchases.collect(
				groupingBy(
					Purchase::employee,
					summingMonetaryAmount(
						AppLogsDemo.CURRENCY_UNIT,
						Purchase::total
					)
				)
			)
		);
	}

}
