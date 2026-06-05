package com.movieapp.service;

import com.movieapp.dto.BookingRequest;
import com.movieapp.entity.Booking;
import com.movieapp.entity.Booking.BookingStatus;
import com.movieapp.entity.Show;
import com.movieapp.entity.Theater;
import com.movieapp.entity.User;
import com.movieapp.exception.ForbiddenException;
import com.movieapp.repository.BookingRepository;
import com.movieapp.repository.ShowRepository;
import com.movieapp.repository.TheaterRepository;
import com.movieapp.repository.UserRepository;
import com.movieapp.util.LanguageMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class BookingService {

    /** GST-style sales tax applied on top of (subtotal - discount). */
    public static final BigDecimal TAX_RATE = new BigDecimal("0.04");

    private final BookingRepository bookingRepository;
    private final ShowRepository showRepository;
    private final UserRepository userRepository;
    private final TheaterRepository theaterRepository;

    public BookingService(BookingRepository bookingRepository,
                          ShowRepository showRepository,
                          UserRepository userRepository,
                          TheaterRepository theaterRepository) {
        this.bookingRepository = bookingRepository;
        this.showRepository = showRepository;
        this.userRepository = userRepository;
        this.theaterRepository = theaterRepository;
    }

    @Transactional
    public Booking create(BookingRequest req) {
        if (req.getUserId() == null || req.getShowId() == null
                || req.getSeats() == null || req.getSeats().isEmpty()) {
            throw new IllegalArgumentException("Invalid booking request");
        }

        User user = userRepository.findById(req.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Show show = showRepository.findById(req.getShowId())
                .orElseThrow(() -> new IllegalArgumentException("Show not found"));

        if (show.isDeleted() || (show.getMovie() != null && show.getMovie().isDeleted())) {
            throw new IllegalStateException("This show is no longer available");
        }

        if (show.getShowTime().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("This show has already started — booking is closed");
        }

        // Defensive: legacy shows may carry a language that isn't in the movie's
        // declared list (older data, manual edits). Refuse booking those rather
        // than letting the user buy a ticket for a language the movie can't be
        // played in.
        String movieLanguages = show.getMovie() != null ? show.getMovie().getLanguages() : null;
        String showLanguage = show.getLanguage();
        if (LanguageMatcher.hasAny(movieLanguages)) {
            if (showLanguage == null || showLanguage.isBlank()) {
                throw new IllegalStateException(
                    "This show has no language set, but the movie is available in: " + movieLanguages +
                    ". Ask the theater to re-schedule the show with one of these languages.");
            }
            if (!LanguageMatcher.matches(movieLanguages, showLanguage)) {
                throw new IllegalStateException(
                    "This show is listed in " + showLanguage +
                    ", which is not among the movie's available languages (" + movieLanguages +
                    "). Booking is blocked — please pick a different show.");
            }
        }

        int seatCount = req.getSeats().size();
        if (show.getAvailableSeats() < seatCount) {
            throw new IllegalStateException("Not enough seats available");
        }

        Set<String> alreadyBooked = collectBookedSeats(req.getShowId());
        for (String s : req.getSeats()) {
            if (alreadyBooked.contains(s)) {
                throw new IllegalStateException("Seat " + s + " is already booked");
            }
        }

        show.setAvailableSeats(show.getAvailableSeats() - seatCount);
        showRepository.save(show);

        BigDecimal subtotal = show.getTicketPrice().multiply(BigDecimal.valueOf(seatCount));
        BigDecimal discount = BigDecimal.ZERO;
        String appliedCoupon = null;

        String requestedCode = req.getCouponCode() != null ? req.getCouponCode().trim().toUpperCase() : null;
        if (requestedCode != null && !requestedCode.isEmpty()
                && show.getCouponCode() != null
                && show.getCouponCode().equalsIgnoreCase(requestedCode)
                && show.getDiscountPercent() != null) {
            discount = subtotal
                    .multiply(BigDecimal.valueOf(show.getDiscountPercent()))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            appliedCoupon = show.getCouponCode();
        }

        BigDecimal taxableBase = subtotal.subtract(discount);
        BigDecimal tax = taxableBase.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = taxableBase.add(tax).setScale(2, RoundingMode.HALF_UP);

        Booking booking = new Booking();
        booking.setUser(user);
        booking.setShow(show);
        booking.setSeatsBooked(seatCount);
        booking.setSeats(String.join(",", req.getSeats()));
        booking.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));
        booking.setDiscountAmount(discount);
        booking.setTaxAmount(tax);
        booking.setCouponApplied(appliedCoupon);
        booking.setTotalAmount(total);
        booking.setStatus(BookingStatus.CONFIRMED);
        return bookingRepository.save(booking);
    }

    @Transactional
    public Booking cancel(Long bookingId, Long requesterId, boolean isAdmin) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found"));

        if (!isAdmin && (requesterId == null || !booking.getUser().getId().equals(requesterId))) {
            throw new ForbiddenException("You cannot cancel another user's booking");
        }

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new IllegalStateException("Booking is already cancelled");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime showTime = booking.getShow().getShowTime();
        long hoursUntil = Duration.between(now, showTime).toHours();

        if (showTime.isBefore(now)) {
            throw new IllegalStateException("Show has already started — cannot cancel");
        }
        if (hoursUntil < 2) {
            throw new IllegalStateException("Cannot cancel less than 2 hours before showtime");
        }

        BigDecimal feeRate = (hoursUntil >= 24)
                ? new BigDecimal("0.10")
                : new BigDecimal("0.50");

        BigDecimal refund = booking.getTotalAmount()
                .multiply(BigDecimal.ONE.subtract(feeRate))
                .setScale(2, RoundingMode.HALF_UP);

        Show show = booking.getShow();
        show.setAvailableSeats(show.getAvailableSeats() + booking.getSeatsBooked());
        showRepository.save(show);

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(now);
        booking.setRefundAmount(refund);
        return bookingRepository.save(booking);
    }

    public List<Booking> findByUser(Long userId) {
        return bookingRepository.findByUserIdOrderByBookingDateDesc(userId);
    }

    /** Admin "All Bookings" — scoped to the admin's theater. */
    public List<Booking> findForAdmin(Long adminUserId) {
        if (adminUserId == null) {
            throw new ForbiddenException("Missing admin identity");
        }
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new ForbiddenException("Unknown admin user"));
        if (admin.getTheaterId() == null) {
            return List.of();
        }
        return bookingRepository.findByTheaterId(admin.getTheaterId());
    }

    public List<String> findBookedSeats(Long showId) {
        List<String> seats = new ArrayList<>();
        for (Booking b : bookingRepository.findByShowId(showId)) {
            if (b.getStatus() == BookingStatus.CANCELLED) continue;
            if (b.getSeats() != null && !b.getSeats().isBlank()) {
                for (String s : b.getSeats().split(",")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) seats.add(trimmed);
                }
            }
        }
        return seats;
    }

    private Set<String> collectBookedSeats(Long showId) {
        return new HashSet<>(findBookedSeats(showId));
    }
}
