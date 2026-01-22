package com.pobox.chas66;

/**
 * Represents a dimension in the pairwise testing problem.
 * Each dimension has an ID and a size (number of features/values).
 *
 * @param id Unique identifier for this dimension (0-indexed)
 * @param size Number of features in this dimension (1-52)
 */
public record Dimension(int id, int size) {

    /**
     * Compact constructor with validation.
     */
    public Dimension {
        if (size <= 0 || size > 52) {
            throw new IllegalArgumentException(
                    "Dimension size must be 1-52 (a-z, A-Z), got: " + size
            );
        }
    }

    /**
     * Backward compatibility getter for id.
     */
    public int getId() {
        return id;
    }

    /**
     * Backward compatibility getter for size.
     */
    public int getSize() {
        return size;
    }
}
