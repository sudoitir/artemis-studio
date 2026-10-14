package io.github.sudoitir.rahdar.kernel.lifecycle.partition;

/**
 * One range-partitioned table that {@link PartitionMaintainer} keeps partitions for and
 * {@link PartitionDrops} reclaims. The owning module contributes it as a bean.
 *
 * @param table the partitioned parent, unqualified: it resolves in the current schema
 * @param column the {@code timestamptz} partition column
 * @param ahead how many periods after the current one exist at any time
 * @param storageParams the heap parameters of every partition, as in {@code WITH (...)} without the
 *     parentheses, or empty: a partitioned parent has no heap, so each partition gets them
 */
public record PartitionSpec(String table, String column, Grain grain, int ahead, String storageParams) {}
