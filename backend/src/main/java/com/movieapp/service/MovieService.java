package com.movieapp.service;

import com.movieapp.entity.Booking.BookingStatus;
import com.movieapp.entity.Movie;
import com.movieapp.entity.Show;
import com.movieapp.repository.BookingRepository;
import com.movieapp.repository.MovieRepository;
import com.movieapp.repository.ShowRepository;
import com.movieapp.util.LanguageMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class MovieService {

    private final MovieRepository movieRepository;
    private final ShowRepository showRepository;
    private final BookingRepository bookingRepository;
    private final ReviewService reviewService;

    public MovieService(MovieRepository movieRepository,
                        ShowRepository showRepository,
                        BookingRepository bookingRepository,
                        ReviewService reviewService) {
        this.movieRepository = movieRepository;
        this.showRepository = showRepository;
        this.bookingRepository = bookingRepository;
        this.reviewService = reviewService;
    }

    public List<Movie> findAll(Long theaterId, String location, String language) {
        List<Movie> movies;
        String loc = (location == null || location.isBlank()) ? null : location.trim();
        if (theaterId != null) {
            // Distinct (non-deleted) movies that have at least one live show in the given theater.
            List<Long> movieIds = showRepository
                    .findByTheaterIdAndDeletedFalseAndMovieDeletedFalseOrderByShowTimeAsc(theaterId).stream()
                    .map(s -> s.getMovie().getId())
                    .distinct()
                    .toList();
            movies = movieIds.isEmpty() ? List.of() : movieRepository.findByIdInAndDeletedFalse(movieIds);
        } else if (loc != null) {
            List<Long> movieIds = showRepository.findMovieIdsByLocation(loc);
            movies = movieIds.isEmpty() ? List.of() : movieRepository.findByIdInAndDeletedFalse(movieIds);
        } else {
            movies = movieRepository.findByDeletedFalse();
        }

        String lang = (language == null || language.isBlank()) ? null : language.trim();
        if (lang != null) {
            movies = movies.stream()
                    .filter(m -> LanguageMatcher.matches(m.getLanguages(), lang))
                    .toList();
        }

        applyRatings(movies);
        return movies;
    }

    private void applyRatings(List<Movie> movies) {
        Map<Long, double[]> ratings = reviewService.aggregateAll();
        for (Movie m : movies) {
            double[] agg = ratings.get(m.getId());
            if (agg != null) {
                m.setAverageRating(Math.round(agg[0] * 10.0) / 10.0);
                m.setReviewCount((long) agg[1]);
            } else {
                m.setAverageRating(null);
                m.setReviewCount(0L);
            }
        }
    }

    public Movie create(Movie movie) {
        if (movie.getTitle() == null || movie.getTitle().isBlank()) {
            throw new IllegalArgumentException("Movie title is required");
        }
        return movieRepository.save(movie);
    }

    public Movie update(Long id, Movie updates) {
        Movie existing = movieRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Movie not found"));

        if (updates.getTitle() != null && !updates.getTitle().isBlank()) {
            existing.setTitle(updates.getTitle());
        }
        if (updates.getGenre() != null) {
            existing.setGenre(updates.getGenre());
        }
        if (updates.getDurationMins() != null) {
            existing.setDurationMins(updates.getDurationMins());
        }
        if (updates.getPosterUrl() != null) {
            existing.setPosterUrl(updates.getPosterUrl());
        }
        if (updates.getPrice() != null) {
            existing.setPrice(updates.getPrice());
        }
        if (updates.getTrailerUrl() != null) {
            existing.setTrailerUrl(updates.getTrailerUrl());
        }
        if (updates.getLanguages() != null) {
            existing.setLanguages(updates.getLanguages());
        }
        return movieRepository.save(existing);
    }

    @Transactional
    public void delete(Long movieId) {
        Movie movie = movieRepository.findById(movieId)
                .orElseThrow(() -> new IllegalArgumentException("Movie not found"));

        LocalDateTime threshold = LocalDateTime.now();
        if (movie.getDurationMins() != null && movie.getDurationMins() > 0) {
            // A show still in progress should keep blocking deletion: any show that
            // started within the last `durationMins` minutes hasn't ended yet.
            threshold = threshold.minusMinutes(movie.getDurationMins());
        }
        long activeBookings = bookingRepository.countActiveByMovieId(
                movieId, BookingStatus.CONFIRMED, threshold);
        if (activeBookings > 0) {
            throw new IllegalStateException(
                "Cannot delete: " + activeBookings + " active booking(s) for upcoming or in-progress shows. " +
                "Cancel them or wait until those shows finish.");
        }

        // Soft-delete: hide the movie and its shows. The bookings, shows, and movie rows are
        // all preserved so the booking -> show -> movie history and analytics keep resolving.
        List<Show> shows = showRepository.findByMovieIdOrderByShowTimeAsc(movieId);
        for (Show s : shows) {
            s.setDeleted(true);
        }
        showRepository.saveAll(shows);

        movie.setDeleted(true);
        movieRepository.save(movie);
    }
}
