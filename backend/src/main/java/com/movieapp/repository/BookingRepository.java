package com.movieapp.repository;

import com.movieapp.entity.Booking;
import com.movieapp.entity.Booking.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByUserIdOrderByBookingDateDesc(Long userId);
    List<Booking> findAllByOrderByBookingDateDesc();
    List<Booking> findByShowId(Long showId);

    @Query("SELECT COUNT(b) FROM Booking b " +
           "WHERE b.show.id = :showId AND b.status = :status " +
           "AND b.show.showTime > :threshold")
    long countActiveByShowId(@Param("showId") Long showId,
                             @Param("status") BookingStatus status,
                             @Param("threshold") LocalDateTime threshold);

    @Query("SELECT COUNT(b) FROM Booking b " +
           "WHERE b.show.movie.id = :movieId AND b.status = :status " +
           "AND b.show.showTime > :threshold")
    long countActiveByMovieId(@Param("movieId") Long movieId,
                              @Param("status") BookingStatus status,
                              @Param("threshold") LocalDateTime threshold);

    @Modifying
    @Query("DELETE FROM Booking b WHERE b.show.id = :showId")
    void deleteByShowId(@Param("showId") Long showId);

    @Modifying
    @Query("DELETE FROM Booking b WHERE b.show.id IN :showIds")
    void deleteByShowIdIn(@Param("showIds") List<Long> showIds);

    @Query("SELECT b FROM Booking b WHERE b.show.theater.id = :theaterId ORDER BY b.bookingDate DESC")
    List<Booking> findByTheaterId(@Param("theaterId") Long theaterId);
}
