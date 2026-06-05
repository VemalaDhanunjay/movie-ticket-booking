package com.movieapp.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "shows")
public class Show {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "theater_id")
    private Theater theater;

    @Column(name = "show_time", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime showTime;

    @Column(name = "ticket_price", nullable = false, precision = 8, scale = 2)
    private BigDecimal ticketPrice;

    @Column(name = "total_seats", nullable = false)
    private Integer totalSeats;

    @Column(name = "available_seats", nullable = false)
    private Integer availableSeats;

    @Column(name = "coupon_code", length = 30)
    private String couponCode;

    @Column(name = "discount_percent")
    private Integer discountPercent;

    @Column(name = "language", length = 40)
    private String language;

    /** Soft-delete flag. Deleted shows are hidden from listings, but their bookings are preserved. */
    @Column(name = "deleted", nullable = false)
    @ColumnDefault("false")
    private boolean deleted = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Movie getMovie() { return movie; }
    public void setMovie(Movie movie) { this.movie = movie; }
    public Theater getTheater() { return theater; }
    public void setTheater(Theater theater) { this.theater = theater; }
    public LocalDateTime getShowTime() { return showTime; }
    public void setShowTime(LocalDateTime showTime) { this.showTime = showTime; }
    public BigDecimal getTicketPrice() { return ticketPrice; }
    public void setTicketPrice(BigDecimal ticketPrice) { this.ticketPrice = ticketPrice; }
    public Integer getTotalSeats() { return totalSeats; }
    public void setTotalSeats(Integer totalSeats) { this.totalSeats = totalSeats; }
    public Integer getAvailableSeats() { return availableSeats; }
    public void setAvailableSeats(Integer availableSeats) { this.availableSeats = availableSeats; }
    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String couponCode) { this.couponCode = couponCode; }
    public Integer getDiscountPercent() { return discountPercent; }
    public void setDiscountPercent(Integer discountPercent) { this.discountPercent = discountPercent; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}
