import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule, DatePipe, CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MovieService } from '../../../core/services/movie.service';
import { ShowService } from '../../../core/services/show.service';
import { BookingService } from '../../../core/services/booking.service';
import { Movie } from '../../../core/models/movie.model';
import { Show } from '../../../core/models/show.model';
import { PosterCarouselComponent } from '../../../shared/poster-carousel/poster-carousel';
import { IconComponent } from '../../../shared/icon/icon';

interface DeleteShowImpact {
  show: Show;
  activeBookings: number;
  cancelledBookings: number;
}

@Component({
  selector: 'app-manage-shows',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, DatePipe, CurrencyPipe, PosterCarouselComponent, IconComponent],
  templateUrl: './manage-shows.html',
  styleUrls: ['./manage-shows.css']
})
export class ManageShowsComponent implements OnInit {
  private movieService = inject(MovieService);
  private showService = inject(ShowService);
  private bookingService = inject(BookingService);

  movies = signal<Movie[]>([]);
  shows = signal<Show[]>([]);
  saving = signal(false);
  msg = signal<string | null>(null);
  err = signal<string | null>(null);

  deleteTarget = signal<DeleteShowImpact | null>(null);
  deleting = signal(false);
  deleteError = signal<string | null>(null);

  minDate = this.toDateInput(new Date());

  form: {
    movieId: number | null;
    date: string;
    times: string[];
    ticketPrice: number;
    totalSeats: number;
    couponCode: string;
    discountPercent: number | null;
    language: string;
  } = {
    movieId: null,
    date: '',
    times: [''],
    ticketPrice: 0,
    totalSeats: 0,
    couponCode: '',
    discountPercent: null,
    language: ''
  };

  /**
   * Languages declared on the currently selected movie, parsed from its CSV.
   * Kept as a plain method (not a `computed`) because `form.movieId` is a
   * direct field mutation, not a signal — a computed would never re-run.
   * Angular's change detection calls this on every cycle, which is cheap
   * since the list is tiny.
   */
  selectedMovieLanguages(): string[] {
    const mid = this.form.movieId;
    if (mid == null) return [];
    const m = this.movies().find(x => x.id === mid);
    if (!m?.languages) return [];
    return m.languages.split(',').map(s => s.trim()).filter(s => s.length > 0);
  }

  /** Picking a different movie must reset the picked language so a stale value can't carry over. */
  selectMovie(id: number | null) {
    this.form.movieId = id;
    this.form.language = '';
  }

  /**
   * Submit is only valid when:
   *   - a movie is chosen,
   *   - the movie has at least one declared language, and
   *   - the picked language is one of those.
   * No free-text fallback is allowed.
   */
  canScheduleLanguage(): boolean {
    if (this.form.movieId == null) return false;
    const langs = this.selectedMovieLanguages();
    if (langs.length === 0) return false;
    const picked = this.form.language?.trim();
    if (!picked) return false;
    return langs.some(l => l.toLowerCase() === picked.toLowerCase());
  }

  ngOnInit() {
    this.movieService.getAll().subscribe(list => this.movies.set(list));
    this.loadShows();
  }

  loadShows() {
    this.showService.getAll().subscribe(list => this.shows.set(list));
  }

  addTime() { this.form.times = [...this.form.times, '']; }
  removeTime(i: number) {
    if (this.form.times.length === 1) return;
    this.form.times = this.form.times.filter((_, idx) => idx !== i);
  }

  schedule() {
    if (this.form.movieId === null || !this.form.date) return;
    if (!this.canScheduleLanguage()) {
      this.err.set('Pick one of the movie\'s declared languages for this show.');
      return;
    }
    const cleanTimes = this.form.times.filter(t => t && t.length > 0);
    if (cleanTimes.length === 0) return;

    const now = Date.now();
    const showTimes = cleanTimes.map(t => `${this.form.date}T${t.length === 5 ? t + ':00' : t}`);
    for (const iso of showTimes) {
      if (new Date(iso).getTime() <= now) {
        this.err.set('All time slots must be in the future.');
        return;
      }
    }

    this.saving.set(true);
    this.msg.set(null);
    this.err.set(null);
    this.showService.bulkCreate({
      movieId: this.form.movieId,
      showTimes,
      ticketPrice: this.form.ticketPrice,
      totalSeats: this.form.totalSeats,
      couponCode: this.form.couponCode?.trim() ? this.form.couponCode.trim().toUpperCase() : null,
      discountPercent: this.form.discountPercent && this.form.couponCode?.trim() ? this.form.discountPercent : null,
      language: this.form.language?.trim() ? this.form.language.trim() : null
    }).subscribe({
      next: created => {
        this.saving.set(false);
        this.msg.set(`Scheduled ${created.length} show(s).`);
        this.reset();
        this.loadShows();
        setTimeout(() => this.msg.set(null), 2500);
      },
      error: e => {
        this.saving.set(false);
        this.err.set(e?.error?.error ?? 'Failed to schedule');
      }
    });
  }

  reset() {
    this.form = { movieId: null, date: '', times: [''], ticketPrice: 0, totalSeats: 0, couponCode: '', discountPercent: null, language: '' };
  }

  openDeleteShow(s: Show) {
    // Best-effort impact counts using current bookings list (admin already loaded it via bookings page when needed).
    // We do a fresh fetch to be accurate.
    this.bookingService.getAll().subscribe({
      next: list => {
        const showBookings = list.filter(b => b.show?.id === s.id);
        // Confirmed bookings only block deletion while the show hasn't ended yet
        // (showTime + the movie's runtime). A finished show never blocks.
        const stillActive = this.showNotEnded(s, Date.now());
        this.deleteTarget.set({
          show: s,
          activeBookings: stillActive ? showBookings.filter(b => b.status === 'CONFIRMED').length : 0,
          cancelledBookings: showBookings.filter(b => b.status === 'CANCELLED').length
        });
        this.deleteError.set(null);
      },
      error: () => {
        this.deleteTarget.set({ show: s, activeBookings: 0, cancelledBookings: 0 });
        this.deleteError.set(null);
      }
    });
  }

  closeDeleteShow() {
    if (this.deleting()) return;
    this.deleteTarget.set(null);
    this.deleteError.set(null);
  }

  confirmDeleteShow() {
    const t = this.deleteTarget();
    if (!t) return;
    this.deleting.set(true);
    this.deleteError.set(null);
    this.showService.delete(t.show.id).subscribe({
      next: () => {
        this.deleting.set(false);
        this.closeDeleteShow();
        this.msg.set('Show deleted.');
        this.loadShows();
        setTimeout(() => this.msg.set(null), 2500);
      },
      error: err => {
        this.deleting.set(false);
        this.deleteError.set(err?.error?.error ?? 'Deletion failed');
      }
    });
  }

  isPast(s: Show): boolean {
    return new Date(s.showTime).getTime() <= Date.now();
  }

  /** A show keeps blocking deletion until showTime + the movie's runtime has elapsed. */
  private showNotEnded(show: Show, nowMs: number): boolean {
    const start = new Date(show.showTime).getTime();
    const dur = show.movie?.durationMins;
    const endMs = (dur && dur > 0) ? start + dur * 60_000 : start;
    return endMs > nowMs;
  }

  private toDateInput(d: Date): string {
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  }

  onImgError(e: Event) {
    (e.target as HTMLImageElement).style.display = 'none';
  }
}
