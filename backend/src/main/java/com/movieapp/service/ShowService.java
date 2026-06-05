package com.movieapp.service;

import com.movieapp.dto.BulkShowRequest;
import com.movieapp.dto.ShowRequest;
import com.movieapp.entity.Booking.BookingStatus;
import com.movieapp.entity.Movie;
import com.movieapp.entity.Show;
import com.movieapp.entity.Theater;
import com.movieapp.entity.User;
import com.movieapp.exception.ForbiddenException;
import com.movieapp.repository.BookingRepository;
import com.movieapp.repository.MovieRepository;
import com.movieapp.repository.ShowRepository;
import com.movieapp.repository.TheaterRepository;
import com.movieapp.repository.UserRepository;
import com.movieapp.util.LanguageMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ShowService {

    private final ShowRepository showRepository;
    private final MovieRepository movieRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final TheaterRepository theaterRepository;

    public ShowService(ShowRepository showRepository,
                       MovieRepository movieRepository,
                       BookingRepository bookingRepository,
                       UserRepository userRepository,
                       TheaterRepository theaterRepository) {
        this.showRepository = showRepository;
        this.movieRepository = movieRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.theaterRepository = theaterRepository;
    }

    public List<Show> findByMovie(Long movieId) {
        return showRepository.findByMovieIdAndDeletedFalseAndMovieDeletedFalseOrderByShowTimeAsc(movieId);
    }

    public List<Show> search(Long movieId, Long theaterId, String location, java.time.LocalDate date) {
        LocalDateTime dayStart = date != null ? date.atStartOfDay() : null;
        LocalDateTime dayEnd = date != null ? date.plusDays(1).atStartOfDay() : null;
        String loc = (location == null || location.isBlank()) ? null : location.trim();
        return showRepository.search(movieId, theaterId, loc, dayStart, dayEnd);
    }

    /** Used by admin "Manage Shows". Scoped to admin's theater. */
    public List<Show> findForAdmin(Long adminUserId) {
        Theater theater = requireAdminTheater(adminUserId);
        return showRepository.findByTheaterIdAndDeletedFalseAndMovieDeletedFalseOrderByShowTimeAsc(theater.getId());
    }

    public Show create(ShowRequest req, Long adminUserId) {
        if (req.getMovieId() == null || req.getShowTime() == null
                || req.getTicketPrice() == null || req.getTotalSeats() == null) {
            throw new IllegalArgumentException("All show fields are required");
        }
        if (req.getShowTime().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Show time must be in the future");
        }
        Theater theater = requireAdminTheater(adminUserId);
        Movie movie = movieRepository.findById(req.getMovieId())
                .orElseThrow(() -> new IllegalArgumentException("Movie not found"));
        if (movie.isDeleted()) {
            throw new IllegalArgumentException("Cannot schedule a show on a deleted movie");
        }

        String language = resolveShowLanguage(movie, req.getLanguage());

        Show show = new Show();
        show.setMovie(movie);
        show.setTheater(theater);
        show.setShowTime(req.getShowTime());
        show.setTicketPrice(req.getTicketPrice());
        show.setTotalSeats(req.getTotalSeats());
        show.setAvailableSeats(req.getTotalSeats());
        show.setLanguage(language);
        applyCoupon(show, req.getCouponCode(), req.getDiscountPercent());
        return showRepository.save(show);
    }

    /**
     * Resolves and validates the language for a show against the movie's declared list.
     * Rules:
     *   - Movie must have at least one declared language before any show can be scheduled.
     *   - The show's language is required and must match (case-insensitive) one of the movie's languages.
     *   - Returns the canonical form (matching the movie's casing) so the DB stays consistent.
     */
    private String resolveShowLanguage(Movie movie, String requestedLanguage) {
        if (!LanguageMatcher.hasAny(movie.getLanguages())) {
            throw new IllegalArgumentException(
                "Movie \"" + movie.getTitle() + "\" has no declared languages. " +
                "Add languages on the movie before scheduling shows.");
        }
        String trimmed = requestedLanguage == null ? null : requestedLanguage.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                "Show language is required and must be one of: " + movie.getLanguages());
        }
        for (String canonical : LanguageMatcher.parse(movie.getLanguages())) {
            if (canonical.equalsIgnoreCase(trimmed)) return canonical;
        }
        throw new IllegalArgumentException(
            "Language \"" + trimmed + "\" is not in this movie's available languages (" +
            movie.getLanguages() + ").");
    }

    private void applyCoupon(Show show, String code, Integer percent) {
        String trimmed = code != null ? code.trim() : null;
        if (trimmed == null || trimmed.isEmpty() || percent == null) {
            show.setCouponCode(null);
            show.setDiscountPercent(null);
            return;
        }
        if (percent < 1 || percent > 100) {
            throw new IllegalArgumentException("Discount percent must be between 1 and 100");
        }
        show.setCouponCode(trimmed.toUpperCase());
        show.setDiscountPercent(percent);
    }

    @Transactional
    public List<Show> bulkCreate(BulkShowRequest req, Long adminUserId) {
        if (req.getMovieId() == null || req.getShowTimes() == null || req.getShowTimes().isEmpty()
                || req.getTicketPrice() == null || req.getTotalSeats() == null) {
            throw new IllegalArgumentException("Movie, at least one show time, price, and seats are all required");
        }
        LocalDateTime now = LocalDateTime.now();
        for (LocalDateTime t : req.getShowTimes()) {
            if (t == null) throw new IllegalArgumentException("Show time cannot be empty");
            if (t.isBefore(now)) throw new IllegalArgumentException("All show times must be in the future");
        }

        Theater theater = requireAdminTheater(adminUserId);
        Movie movie = movieRepository.findById(req.getMovieId())
                .orElseThrow(() -> new IllegalArgumentException("Movie not found"));
        if (movie.isDeleted()) {
            throw new IllegalArgumentException("Cannot schedule a show on a deleted movie");
        }

        String language = resolveShowLanguage(movie, req.getLanguage());

        List<Show> created = new ArrayList<>();
        for (LocalDateTime t : req.getShowTimes()) {
            Show show = new Show();
            show.setMovie(movie);
            show.setTheater(theater);
            show.setShowTime(t);
            show.setTicketPrice(req.getTicketPrice());
            show.setTotalSeats(req.getTotalSeats());
            show.setAvailableSeats(req.getTotalSeats());
            show.setLanguage(language);
            applyCoupon(show, req.getCouponCode(), req.getDiscountPercent());
            created.add(showRepository.save(show));
        }
        return created;
    }

    /** Shows in any theater that currently have a coupon attached, ordered by next showtime. */
    public List<Show> findOffers() {
        return showRepository.findShowsWithCoupons(LocalDateTime.now());
    }

    @Transactional
    public void delete(Long showId, Long adminUserId) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new IllegalArgumentException("Show not found"));

        // Owners can only delete their own theater's shows.
        Theater theater = requireAdminTheater(adminUserId);
        if (show.getTheater() == null || !theater.getId().equals(show.getTheater().getId())) {
            throw new ForbiddenException("You can only delete shows in your own theater");
        }

        LocalDateTime threshold = LocalDateTime.now();
        Integer durationMins = show.getMovie() != null ? show.getMovie().getDurationMins() : null;
        if (durationMins != null && durationMins > 0) {
            threshold = threshold.minusMinutes(durationMins);
        }
        long active = bookingRepository.countActiveByShowId(
                showId, BookingStatus.CONFIRMED, threshold);
        if (active > 0) {
            throw new IllegalStateException(
                "Cannot delete: " + active + " active booking(s) for this show (not finished yet).");
        }

        // Soft-delete: hide the show. Its bookings are preserved for history and analytics.
        show.setDeleted(true);
        showRepository.save(show);
    }

    private Theater requireAdminTheater(Long adminUserId) {
        if (adminUserId == null) {
            throw new ForbiddenException("Missing admin identity");
        }
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new ForbiddenException("Unknown admin user"));
        if (admin.getTheaterId() == null) {
            throw new IllegalStateException("You don't have a theater yet — set one up first");
        }
        return theaterRepository.findById(admin.getTheaterId())
                .orElseThrow(() -> new IllegalStateException("Admin's theater no longer exists"));
    }
}
