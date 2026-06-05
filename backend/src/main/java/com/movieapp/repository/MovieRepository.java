package com.movieapp.repository;

import com.movieapp.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MovieRepository extends JpaRepository<Movie, Long> {
    // Catalog/listing reads must hide soft-deleted movies.
    List<Movie> findByDeletedFalse();
    List<Movie> findByIdInAndDeletedFalse(List<Long> ids);
}
