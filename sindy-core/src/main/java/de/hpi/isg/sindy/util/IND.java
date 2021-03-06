package de.hpi.isg.sindy.util;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import scala.Int;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Simple implementation of an inclusion dependency via column IDs.
 */
public class IND {

    /**
     * An instance without any columns.
     */
    public static final IND emptyInstance = new IND(new int[0], new int[0]);

    /**
     * Standard {@link Comparator} for {@link IND}s that orders by
     * <ol>
     *     <li>arity,</li>
     *     <li>dependent column IDs (lexicographically), and</li>
     *     <li>referenced column IDs (lexicographically)</li>
     * </ol>
     * in that order.
     */
    public static final Comparator<IND> standardComparator = (ind1, ind2) -> {
        if (ind1 == ind2) return 0;

        int result = Integer.compare(ind1.getArity(), ind2.getArity());
        if (result != 0) return result;

        int arity = ind1.getArity();
        for (int i = 0; i < arity; i++) {
            result = Integer.compare(ind1.dependentColumns[i], ind2.dependentColumns[i]);
            if (result != 0) return result;
        }
        for (int i = 0; i < arity; i++) {
            result = Integer.compare(ind1.referencedColumns[i], ind2.referencedColumns[i]);
            if (result != 0) return result;
        }

        return 0;
    };

    /**
     * Lexicographical {@link Comparator} for {@link IND}s that orders by
     * <ol>
     *     <li>dependent column IDs (lexicographically), and</li>
     *     <li>referenced column IDs (lexicographically)</li>
     * </ol>
     * in that order. Note that the second comparison criterion can only apply when both {@link IND}s are of the same
     * arity.
     */
    public static final Comparator<IND> lexicographicalComparator = (ind1, ind2) -> {
        if (ind1 == ind2) return 0;

        // Sort lexicographically by the dep. columns.
        int result;
        int minArity = Math.min(ind1.getArity(), ind2.getArity());
        for (int i = 0; i < minArity; i++) {
            result = Integer.compare(ind1.dependentColumns[i], ind2.dependentColumns[i]);
            if (result != 0) return result;
        }
        // If the common dep. columns are identical, the "shorter" IND comes first.
        result = Integer.compare(ind1.getArity(), ind2.getArity());
        if (result != 0) return result;
        // Sort lexicographically by the equal-sized ref. columns.
        for (int i = 0; i < minArity; i++) {
            result = Integer.compare(ind1.referencedColumns[i], ind2.referencedColumns[i]);
            if (result != 0) return result;
        }

        return 0;
    };

    /**
     * IDs of the dependent and reference columns of this instance.
     */
    protected final int[] dependentColumns, referencedColumns;

    public IND(int[] dependentColumns, int[] referencedColumns) {
        assert dependentColumns.length == referencedColumns.length;
        this.dependentColumns = dependentColumns;
        this.referencedColumns = referencedColumns;
    }

    /**
     * Creates a new unary IND.
     *
     * @param dependentId  the dependent column ID
     * @param referencedId the referenced column ID
     */
    public IND(int dependentId, int referencedId) {
        this(new int[]{dependentId}, new int[]{referencedId});
    }

    /**
     * The number of dependent columns (and referenced columns, respectively) in this instance.
     */
    public int getArity() {
        return this.dependentColumns.length;
    }

    public int[] getDependentColumns() {
        return this.dependentColumns;
    }

    public int[] getReferencedColumns() {
        return this.referencedColumns;
    }

    /**
     * Tests this inclusion dependency for triviality, i.e., whether the dependent and referenced sides are equal.
     *
     * @return whether this is a trivial inclusion dependency
     */
    public boolean isTrivial() {
        return Arrays.equals(this.dependentColumns, this.referencedColumns);
    }

    /**
     * Checks whether this instance is implied by another instance.
     *
     * @param that is the allegedly implying instance
     * @return whether this instance is implied
     */
    public boolean isImpliedBy(IND that) {
        if (this.getArity() > that.getArity()) {
            return false;
        }

        // Co-iterate the two INDs and make use of the sorting of the column IDs.
        int thisI = 0, thatI = 0;
        while (thisI < this.getArity() && thatI < that.getArity() && (this.getArity() - thisI <= that.getArity() - thatI)) {
            int thisCol = this.dependentColumns[thisI];
            int thatCol = that.dependentColumns[thatI];
            if (thisCol == thatCol) {
                thisCol = this.referencedColumns[thisI];
                thatCol = that.referencedColumns[thatI];
            }
            if (thisCol == thatCol) {
                thisI++;
                thatI++;
            } else if (thisCol > thatCol) {
                thatI++;
            } else {
                return false;
            }
        }

        return thisI == this.getArity();
    }

    /**
     * Creates a projection of this instance where the {@code index} specifies which two columns to keep.
     *
     * @param index the projection index
     * @return the projected instance
     */
    public IND project(int index) {
        return new IND(new int[]{this.dependentColumns[index]}, new int[]{this.referencedColumns[index]});
    }

    /**
     * Creates a projection of this instance where the {@code index} specifies which two columns to leave out.
     *
     * @param index the leave-out index
     * @return the projected instance
     */
    public IND coproject(int index) {
        if (index < 0 || index >= this.getArity()) throw new IndexOutOfBoundsException();
        return new IND(remove(this.dependentColumns, index), remove(this.referencedColumns, index));
    }

    /**
     * Creates a new {@code int} array with one element removed.
     *
     * @param array the original array
     * @param index the index to remove from {@code array}
     * @return a new array with the index removed
     */
    private static int[] remove(int[] array, int index) {
        int[] newArray = new int[array.length - 1];
        System.arraycopy(array, 0, newArray, 0, index);
        System.arraycopy(array, index + 1, newArray, index, array.length - (index + 1));
        return newArray;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || this.getClass() != o.getClass()) return false;
        final IND ind = (IND) o;
        return Arrays.equals(this.dependentColumns, ind.dependentColumns) &&
                Arrays.equals(this.referencedColumns, ind.referencedColumns);
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + Arrays.hashCode(this.dependentColumns);
        result = 31 * result + Arrays.hashCode(this.referencedColumns);
        return result;
    }

    @Override
    public String toString() {
        return String.format("%s \u2286 %s", Arrays.toString(this.dependentColumns), Arrays.toString(this.referencedColumns));
    }

    /**
     * Nicer version of {@link #toString()} that resolves table names and column indices.
     *
     * @param tables        the indexed tables
     * @param numColumnBits the number of bits used to encode columns
     * @return a {@link String} representation of this instance
     */
    public String toString(Int2ObjectMap<String> tables, int numColumnBits) {
        if (this.getArity() == 0) return "[] \u2286 []";
        int columnBitMask = -1 >>> (Integer.SIZE - numColumnBits);

        int depMinColumnId = this.dependentColumns[0] & ~columnBitMask;
        int depTableId = depMinColumnId | columnBitMask;
        int refMinColumnId = this.referencedColumns[0] & ~columnBitMask;
        int refTableId = refMinColumnId | columnBitMask;

        StringBuilder sb = new StringBuilder();
        sb.append(tables.get(depTableId)).append('[');
        String separator = "";
        for (int dependentColumn : this.dependentColumns) {
            sb.append(separator).append(dependentColumn - depMinColumnId);
            separator = ", ";
        }
        sb.append("] \u2286 ").append(tables.get(refTableId)).append("[");
        separator = "";
        for (int referencedColumn : this.referencedColumns) {
            sb.append(separator).append(referencedColumn - refMinColumnId);
            separator = ", ";
        }
        sb.append("]");
        return sb.toString();
    }


}
