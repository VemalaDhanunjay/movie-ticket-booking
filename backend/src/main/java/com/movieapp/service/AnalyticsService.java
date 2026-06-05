package com.movieapp.service;

import com.movieapp.entity.Booking;
import com.movieapp.entity.Booking.BookingStatus;
import com.movieapp.entity.Show;
import com.movieapp.entity.User;
import com.movieapp.exception.ForbiddenException;
import com.movieapp.repository.BookingRepository;
import com.movieapp.repository.ShowRepository;
import com.movieapp.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class AnalyticsService {

    private final BookingRepository bookingRepository;
    private final ShowRepository showRepository;
    private final UserRepository userRepository;

    public AnalyticsService(BookingRepository bookingRepository,
                            ShowRepository showRepository,
                            UserRepository userRepository) {
        this.bookingRepository = bookingRepository;
        this.showRepository = showRepository;
        this.userRepository = userRepository;
    }

    public Map<String, Object> overview(Long adminUserId) {
        Long theaterId = requireTheater(adminUserId);
        List<Booking> bookings = bookingRepository.findByTheaterId(theaterId);
        List<Show> shows = showRepository.findByTheaterIdOrderByShowTimeAsc(theaterId);

        long total = bookings.size();
        long confirmed = bookings.stream().filter(b -> b.getStatus() == BookingStatus.CONFIRMED).count();
        long cancelled = total - confirmed;
        BigDecimal revenue = bookings.stream()
                .map(this::keptRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        long seatsSold = bookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED)
                .mapToLong(Booking::getSeatsBooked)
                .sum();

        LocalDateTime now = LocalDateTime.now();
        long upcomingShows = shows.stream().filter(s -> s.getShowTime().isAfter(now)).count();
        double cancellationRate = total > 0 ? (cancelled * 100.0 / total) : 0.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalBookings", total);
        out.put("confirmedBookings", confirmed);
        out.put("cancelledBookings", cancelled);
        out.put("totalRevenue", revenue);
        out.put("seatsSold", seatsSold);
        out.put("totalShows", (long) shows.size());
        out.put("upcomingShows", upcomingShows);
        out.put("cancellationRate", Math.round(cancellationRate * 10.0) / 10.0);
        return out;
    }

    /** Daily bookings + revenue for the last {@code days} days, oldest first. */
    public List<Map<String, Object>> bookingsOverTime(Long adminUserId, int days) {
        if (days < 1) days = 30;
        if (days > 180) days = 180;
        Long theaterId = requireTheater(adminUserId);

        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(days - 1L);

        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (int i = 0; i < days; i++) byDay.put(from.plusDays(i), new long[]{0, 0});
        Map<LocalDate, BigDecimal> revenueByDay = new HashMap<>();

        for (Booking b : bookingRepository.findByTheaterId(theaterId)) {
            LocalDate d = b.getBookingDate().toLocalDate();
            if (d.isBefore(from)) continue;
            long[] counts = byDay.computeIfAbsent(d, k -> new long[]{0, 0});
            counts[0]++;
            if (b.getStatus() == BookingStatus.CONFIRMED) {
                counts[1]++;
            }
            // Revenue = money kept: the confirmed total, or the retained cancellation fee.
            BigDecimal kept = keptRevenue(b);
            if (kept.signum() > 0) {
                revenueByDay.merge(d, kept, BigDecimal::add);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> e : byDay.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", e.getKey().toString());
            row.put("bookings", e.getValue()[0]);
            row.put("confirmed", e.getValue()[1]);
            row.put("revenue", revenueByDay.getOrDefault(e.getKey(), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
            out.add(row);
        }
        return out;
    }

    public List<Map<String, Object>> revenueByMovie(Long adminUserId) {
        Long theaterId = requireTheater(adminUserId);
        Map<String, BigDecimal> revByTitle = new HashMap<>();
        Map<String, Long> seatsByTitle = new HashMap<>();

        for (Booking b : bookingRepository.findByTheaterId(theaterId)) {
            BigDecimal kept = keptRevenue(b);
            if (kept.signum() <= 0) continue;   // fully refunded / nothing kept
            String title = b.getShow().getMovie().getTitle();
            revByTitle.merge(title, kept, BigDecimal::add);
            // Seats count only for bookings that stayed confirmed (cancelled seats were released).
            if (b.getStatus() == BookingStatus.CONFIRMED) {
                seatsByTitle.merge(title, b.getSeatsBooked().longValue(), Long::sum);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : revByTitle.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", e.getKey());
            row.put("revenue", e.getValue().setScale(2, RoundingMode.HALF_UP));
            row.put("seats", seatsByTitle.getOrDefault(e.getKey(), 0L));
            out.add(row);
        }
        out.sort((a, b) -> ((BigDecimal) b.get("revenue")).compareTo((BigDecimal) a.get("revenue")));
        return out;
    }

    public List<Map<String, Object>> showFillRate(Long adminUserId) {
        Long theaterId = requireTheater(adminUserId);
        List<Show> shows = showRepository.findByTheaterIdOrderByShowTimeAsc(theaterId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Show s : shows) {
            int total = s.getTotalSeats() == null ? 0 : s.getTotalSeats();
            int avail = s.getAvailableSeats() == null ? 0 : s.getAvailableSeats();
            int booked = Math.max(0, total - avail);
            double pct = total > 0 ? (booked * 100.0 / total) : 0.0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("showId", s.getId());
            row.put("movie", s.getMovie().getTitle());
            row.put("showTime", s.getShowTime().toString());
            row.put("totalSeats", total);
            row.put("bookedSeats", booked);
            row.put("fillPercent", Math.round(pct * 10.0) / 10.0);
            out.add(row);
        }
        out.sort(Comparator.comparing(r -> (String) r.get("showTime")));
        return out;
    }

    /**
     * Money the theater actually keeps for a booking:
     *   - CONFIRMED: the full amount paid.
     *   - CANCELLED: only the non-refunded portion (the cancellation fee). If the refund
     *     amount is missing (legacy rows), assume nothing was kept rather than inventing revenue.
     */
    private BigDecimal keptRevenue(Booking b) {
        BigDecimal total = b.getTotalAmount() != null ? b.getTotalAmount() : BigDecimal.ZERO;
        if (b.getStatus() == BookingStatus.CONFIRMED) {
            return total;
        }
        BigDecimal refund = b.getRefundAmount() != null ? b.getRefundAmount() : total;
        BigDecimal kept = total.subtract(refund);
        return kept.signum() > 0 ? kept : BigDecimal.ZERO;
    }

    private Long requireTheater(Long adminUserId) {
        if (adminUserId == null) throw new ForbiddenException("Missing admin identity");
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new ForbiddenException("Unknown admin user"));
        if (admin.getTheaterId() == null) throw new ForbiddenException("Admin has no theater");
        return admin.getTheaterId();
    }
}
