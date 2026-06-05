package com.movieapp.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;

@Entity
@Table(name = "movies")
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(length = 50)
    private String genre;

    @Column(name = "duration_mins")
    private Integer durationMins;

    @Column(name = "poster_url", length = 500)
    private String posterUrl;

    /** Default ticket price for this movie. Admins can still override per show. */
    @Column(name = "price", precision = 8, scale = 2)
    private BigDecimal price;

    @Column(name = "trailer_url", length = 500)
    private String trailerUrl;

    /** Comma-separated language list (e.g. "Telugu,Hindi,English"). */
    @Column(name = "languages", length = 250)
    private String languages;

    /** Soft-delete flag. Deleted movies are hidden from the catalog/listings, but their
        rows (and dependent shows/bookings) are preserved for history and analytics. */
    @Column(name = "deleted", nullable = false)
    @ColumnDefault("false")
    private boolean deleted = false;

    /** Populated on read; not persisted. */
    @Transient
    private Double averageRating;

    @Transient
    private Long reviewCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getGenre() { return genre; }
    public void setGenre(String genre) { this.genre = genre; }
    public Integer getDurationMins() { return durationMins; }
    public void setDurationMins(Integer durationMins) { this.durationMins = durationMins; }
    public String getPosterUrl() { return posterUrl; }
    public void setPosterUrl(String posterUrl) { this.posterUrl = posterUrl; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getTrailerUrl() { return trailerUrl; }
    public void setTrailerUrl(String trailerUrl) { this.trailerUrl = trailerUrl; }
    public String getLanguages() { return languages; }
    public void setLanguages(String languages) { this.languages = languages; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public Double getAverageRating() { return averageRating; }
    public void setAverageRating(Double averageRating) { this.averageRating = averageRating; }
    public Long getReviewCount() { return reviewCount; }
    public void setReviewCount(Long reviewCount) { this.reviewCount = reviewCount; }
}
