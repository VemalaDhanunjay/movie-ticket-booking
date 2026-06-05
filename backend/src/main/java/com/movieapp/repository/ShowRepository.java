package com.movieapp.repository;

import com.movieapp.entity.Show;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ShowRepository extends JpaRepository<Show, Long> {
    List<Show> findByMovieIdOrderByShowTimeAsc(Long movieId);
    List<Show> findAllByOrderByShowTimeAsc();
    List<Show> findByTheaterIdOrderByShowTimeAsc(Long theaterId);

    // Listing finders: hide soft-deleted shows AND shows whose movie is soft-deleted.
    // (The raw finders above stay for analytics/history, which must see everything.)
    List<Show> findByTheaterIdAndDeletedFalseAndMovieDeletedFalseOrderByShowTimeAsc(Long theaterId);
    List<Show> findByMovieIdAndDeletedFalseAndMovieDeletedFalseOrderByShowTimeAsc(Long movieId);

    @Query("SELECT s FROM Show s WHERE " +
           "s.deleted = false AND s.movie.deleted = false AND " +
           "(:movieId IS NULL OR s.movie.id = :movieId) AND " +
           "(:theaterId IS NULL OR s.theater.id = :theaterId) AND " +
           "(:location IS NULL OR LOWER(s.theater.location) = LOWER(:location)) AND " +
           "(:dayStart IS NULL OR (s.showTime >= :dayStart AND s.showTime < :dayEnd)) " +
           "ORDER BY s.showTime ASC")
    List<Show> search(@Param("movieId") Long movieId,
                      @Param("theaterId") Long theaterId,
                      @Param("location") String location,
                      @Param("dayStart") LocalDateTime dayStart,
                      @Param("dayEnd") LocalDateTime dayEnd);

    @Query("SELECT s FROM Show s WHERE s.couponCode IS NOT NULL " +
           "AND s.discountPercent IS NOT NULL AND s.showTime > :now " +
           "AND s.deleted = false AND s.movie.deleted = false " +
           "ORDER BY s.showTime ASC")
    List<Show> findShowsWithCoupons(@Param("now") LocalDateTime now);

    @Query("SELECT DISTINCT s.movie.id FROM Show s " +
           "WHERE LOWER(s.theater.location) = LOWER(:location) " +
           "AND s.deleted = false AND s.movie.deleted = false")
    List<Long> findMovieIdsByLocation(@Param("location") String location);
}
