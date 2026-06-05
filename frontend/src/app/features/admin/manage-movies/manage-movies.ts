import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MovieService } from '../../../core/services/movie.service';
import { ShowService } from '../../../core/services/show.service';
import { BookingService } from '../../../core/services/booking.service';
import { Movie } from '../../../core/models/movie.model';
import { Show } from '../../../core/models/show.model';
import { Booking } from '../../../core/models/booking.model';
import { PosterCarouselComponent } from '../../../shared/poster-carousel/poster-carousel';
import { IconComponent } from '../../../shared/icon/icon';
import { toYouTubeEmbedUrl } from '../../../shared/youtube-utils';

interface DeleteImpact {
  movie: Movie;
  totalShows: number;
  upcomingShows: number;
  activeBookings: number;
  cancelledBookings: number;
}

@Component({
  selector: 'app-manage-movies',
  standalone: true,
  imports: [CommonModule, FormsModule, PosterCarouselComponent, IconComponent],
  templateUrl: './manage-movies.html',
  styleUrls: ['./manage-movies.css']
})
export class ManageMoviesComponent implements OnInit {
  private movieService = inject(MovieService);
  private showService = inject(ShowService);
  private bookingService = inject(BookingService);

  movies = signal<Movie[]>([]);
  shows = signal<Show[]>([]);
  bookings = signal<Booking[]>([]);

  saving = signal(false);
  msg = signal<string | null>(null);
  err = signal<string | null>(null);

  deleteTarget = signal<DeleteImpact | null>(null);
  deleting = signal(false);
  deleteError = signal<string | null>(null);

  editTarget = signal<Movie | null>(null);
  editing = signal(false);
  editError = signal<string | null>(null);
  editForm: Omit<Movie, 'id'> = { title: '', genre: '', durationMins: 0, posterUrl: '', price: null, trailerUrl: '', languages: '' };

  form: Omit<Movie, 'id'> = { title: '', genre: '', durationMins: 0, posterUrl: '', price: null, trailerUrl: '', languages: '' };

  readonly suggestedLanguages = ['Telugu', 'Hindi', 'English', 'Tamil', 'Kannada', 'Malayalam'];

  embedPreview(url: string | null | undefined): string | null {
    return toYouTubeEmbedUrl(url);
  }

  hasLanguage(csv: string | null | undefined, lang: string): boolean {
    return this.parseLanguages(csv).some(l => l.toLowerCase() === lang.toLowerCase());
  }

  toggleAddLanguage(lang: string) {
    this.form.languages = this.toggleLanguage(this.form.languages, lang);
  }

  toggleEditLanguage(lang: string) {
    this.editForm.languages = this.toggleLanguage(this.editForm.languages, lang);
  }

  private toggleLanguage(csv: string | null | undefined, lang: string): string {
    const list = this.parseLanguages(csv);
    const idx = list.findIndex(l => l.toLowerCase() === lang.toLowerCase());
    if (idx >= 0) list.splice(idx, 1);
    else list.push(lang);
    return list.join(', ');
  }

  private parseLanguages(csv: string | null | undefined): string[] {
    if (!csv) return [];
    return csv.split(',').map(s => s.trim()).filter(s => s.length > 0);
  }

  ngOnInit() { this.loadAll(); }

  loadAll() {
    this.movieService.getAll().subscribe(list => this.movies.set(list));
    this.showService.getAll().subscribe(list => this.shows.set(list));
    this.bookingService.getAll().subscribe(list => this.bookings.set(list));
  }

  add() {
    this.saving.set(true);
    this.msg.set(null);
    this.err.set(null);
    this.movieService.create(this.form).subscribe({
      next: () => {
        this.saving.set(false);
        this.msg.set('Movie added.');
        this.reset();
        this.loadAll();
        setTimeout(() => this.msg.set(null), 2500);
      },
      error: e => {
        this.saving.set(false);
        const msg = e?.error?.error
                 ?? e?.error?.message
                 ?? e?.message
                 ?? `HTTP ${e?.status ?? '???'} — failed to add movie`;
        this.err.set(msg);
        console.error('Add movie failed:', e);
      }
    });
  }

  reset() {
    this.form = { title: '', genre: '', durationMins: 0, posterUrl: '', price: null, trailerUrl: '', languages: '' };
  }

  openEdit(m: Movie) {
    this.editTarget.set(m);
    this.editForm = {
      title: m.title ?? '',
      genre: m.genre ?? '',
      durationMins: m.durationMins ?? 0,
      posterUrl: m.posterUrl ?? '',
      price: m.price ?? null,
      trailerUrl: m.trailerUrl ?? '',
      languages: m.languages ?? ''
    };
    this.editError.set(null);
  }

  closeEdit() {
    if (this.editing()) return;
    this.editTarget.set(null);
    this.editError.set(null);
  }

  confirmEdit() {
    const t = this.editTarget();
    if (!t) return;
    this.editing.set(true);
    this.editError.set(null);
    this.movieService.update(t.id, this.editForm).subscribe({
      next: () => {
        this.editing.set(false);
        this.closeEdit();
        this.msg.set('Movie updated.');
        this.loadAll();
        setTimeout(() => this.msg.set(null), 2500);
      },
      error: e => {
        this.editing.set(false);
        this.editError.set(e?.error?.error ?? e?.message ?? 'Update failed');
      }
    });
  }

  openDelete(m: Movie) {
    const movieShows = this.shows().filter(s => s.movie.id === m.id);
    const now = Date.now();
    const upcoming = movieShows.filter(s => new Date(s.showTime).getTime() > now).length;
    const movieBookings = this.bookings().filter(b => b.show?.movie?.id === m.id);
    // Only confirmed bookings for shows that haven't ended yet block deletion.
    // A show is "ended" once showTime + the movie's runtime has passed; if the
    // duration is unknown we fall back to showTime alone.
    const active = movieBookings.filter(b =>
      b.status === 'CONFIRMED' && this.showNotEnded(b.show, now)
    ).length;
    const cancelled = movieBookings.filter(b => b.status === 'CANCELLED').length;

    this.deleteTarget.set({
      movie: m,
      totalShows: movieShows.length,
      upcomingShows: upcoming,
      activeBookings: active,
      cancelledBookings: cancelled
    });
    this.deleteError.set(null);
  }

  /** A show keeps blocking deletion until showTime + the movie's runtime has elapsed. */
  private showNotEnded(show: Show, nowMs: number): boolean {
    const start = new Date(show.showTime).getTime();
    const dur = show.movie?.durationMins;
    const endMs = (dur && dur > 0) ? start + dur * 60_000 : start;
    return endMs > nowMs;
  }

  closeDelete() {
    if (this.deleting()) return;
    this.deleteTarget.set(null);
    this.deleteError.set(null);
  }

  confirmDelete() {
    const t = this.deleteTarget();
    if (!t) return;
    this.deleting.set(true);
    this.deleteError.set(null);
    this.movieService.delete(t.movie.id).subscribe({
      next: () => {
        this.deleting.set(false);
        this.closeDelete();
        this.msg.set('Movie deleted.');
        this.loadAll();
        setTimeout(() => this.msg.set(null), 2500);
      },
      error: err => {
        this.deleting.set(false);
        this.deleteError.set(err?.error?.error ?? 'Deletion failed');
      }
    });
  }

  onImgError(e: Event) {
    (e.target as HTMLImageElement).style.display = 'none';
  }
}
