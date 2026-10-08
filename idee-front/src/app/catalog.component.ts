import {
  Component,
  inject,
  signal,
  computed,
  effect,
  untracked,
  ViewChild,
  ElementRef,
  DestroyRef,
  RESPONSE_INIT,
} from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { HttpClient } from "@angular/common/http";
import { FormsModule } from "@angular/forms";
import {
  DateAdapter,
  MAT_DATE_LOCALE,
  provideNativeDateAdapter,
} from "@angular/material/core";
import {
  MatDatepickerModule,
  MatDatepickerIntl,
} from "@angular/material/datepicker";
import { RouterLink } from "@angular/router";
import { Title, Meta } from "@angular/platform-browser";
import { TablerIconsModule } from "angular-tabler-icons";
import { Subscription } from "rxjs";
import { Category, Outing } from "./outing.model";
import { OutingPresentation } from "./outing-presentation";
import { CatalogState } from "./catalog-state.service";
import { SITE_ORIGIN } from "./rendering";
import { LanguageService } from "./language.service";
import { CATALOG_CITIES } from "./catalog-cities";

interface CatalogPage {
  items: Outing[];
  total: number;
  hasMore: boolean;
}

function localizedCalendarLabels() {
  const i18n = inject(LanguageService);
  return Object.assign(new MatDatepickerIntl(), {
    calendarLabel: i18n.t("Choisir une date de sortie"),
    openCalendarLabel: i18n.t("Ouvrir le calendrier"),
    closeCalendarLabel: i18n.t("Fermer le calendrier"),
    prevMonthLabel: i18n.t("Mois précédent"),
    nextMonthLabel: i18n.t("Mois suivant"),
    prevYearLabel: i18n.t("Année précédente"),
    nextYearLabel: i18n.t("Année suivante"),
    prevMultiYearLabel: i18n.t("Années précédentes"),
    nextMultiYearLabel: i18n.t("Années suivantes"),
    switchToMonthViewLabel: i18n.t("Choisir un jour"),
    switchToMultiYearViewLabel: i18n.t("Choisir une année"),
    formatYearRangeLabel: (start: string, end: string) =>
      i18n.t("De {start} à {end}", { start, end }),
  });
}

@Component({
  selector: "idee-catalog",
  standalone: true,
  imports: [FormsModule, RouterLink, TablerIconsModule, MatDatepickerModule],
  providers: [
    provideNativeDateAdapter(),
    { provide: MAT_DATE_LOCALE, useValue: "fr-FR" },
    { provide: MatDatepickerIntl, useFactory: localizedCalendarLabels },
  ],
  templateUrl: "./catalog.component.html",
  styleUrl: "./catalog.component.scss",
})
export class CatalogComponent extends OutingPresentation {
  private destroyRef = inject(DestroyRef);
  private state = inject(CatalogState);
  private title = inject(Title);
  private meta = inject(Meta);
  private http = inject(HttpClient);
  private response = inject(RESPONSE_INIT, { optional: true });
  private request?: Subscription;
  private observer?: IntersectionObserver;
  private params: Record<string, string> = {};
  @ViewChild("results") results!: ElementRef<HTMLElement>;
  @ViewChild("loadTrigger") set loadTrigger(
    element: ElementRef<HTMLElement> | undefined,
  ) {
    this.observer?.disconnect();
    if (!element || typeof IntersectionObserver === "undefined") return;
    this.observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) this.loadMore();
      },
      { rootMargin: "300px" },
    );
    this.observer.observe(element.nativeElement);
  }
  outings = signal<Outing[]>([]);
  categories = signal<Category[]>([]);
  loading = signal(true);
  loadingMore = signal(false);
  error = signal(false);
  moreError = signal(false);
  total = signal(0);
  hasMore = signal(false);
  query = this.state.query;
  category = this.state.category;
  department = this.state.department;
  city = this.state.city;
  period = this.state.period;
  date = this.state.date;
  readonly minDate = new Date(1900, 0, 1);
  readonly maxDate = new Date(2200, 11, 31);
  readonly calendarDate = computed(() => {
    if (this.period() !== "date" || !this.date()) return null;
    const [year, month, day] = this.date().split("-").map(Number);
    return new Date(year, month - 1, day);
  });
  readonly chosenDateLabel = computed(() =>
    this.date() ? this.formatDate(this.date() + "T12:00:00+01:00") : "",
  );
  freeOnly = this.state.freeOnly;
  readonly cities = computed(() =>
    CATALOG_CITIES.filter(
      (city) => !this.department() || city.department === this.department(),
    ).slice(0, 10),
  );
  readonly demo = computed(() => this.outings().some((o) => o.isDemo));
  readonly filtered = computed(() =>
    this.outings().map((o) => this.i18n.localize(o)),
  );
  readonly isWeekend = ["Sat", "Sun"].includes(
    new Intl.DateTimeFormat("en-US", {
      timeZone: "Europe/Paris",
      weekday: "short",
    }).format(new Date()),
  );
  readonly weekPeriod = this.isWeekend ? "next-week" : "week";
  readonly weekLabel = this.isWeekend
    ? "La semaine prochaine"
    : "Cette semaine";

  constructor() {
    super();
    const adapter = inject(DateAdapter);
    const calendar = inject(MatDatepickerIntl);
    const origin = inject(SITE_ORIGIN);
    effect(() => {
      adapter.setLocale(this.i18n.current());
      const labels: Record<string, string> = {
        calendarLabel: "Choisir une date de sortie",
        openCalendarLabel: "Ouvrir le calendrier",
        closeCalendarLabel: "Fermer le calendrier",
        prevMonthLabel: "Mois précédent",
        nextMonthLabel: "Mois suivant",
        prevYearLabel: "Année précédente",
        nextYearLabel: "Année suivante",
        prevMultiYearLabel: "Années précédentes",
        nextMultiYearLabel: "Années suivantes",
        switchToMonthViewLabel: "Choisir un jour",
        switchToMultiYearViewLabel: "Choisir une année",
      };
      Object.assign(
        calendar,
        Object.fromEntries(
          Object.entries(labels).map(([key, value]) => [key, this.t(value)]),
        ),
      );
      calendar.changes.next();
      this.title.setTitle(this.t("Idées de sorties en Alsace"));
      this.meta.updateTag({
        name: "description",
        content: this.t(
          "Trouvez vos idées de sorties en Alsace : nature, culture, marchés et événements.",
        ),
      });
      this.i18n.pageLinks("/", origin);
    });
    this.http
      .get<Category[]>("/api/categories")
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (categories) => this.categories.set(categories),
        error: () => {},
      });
    effect((onCleanup) => {
      const params = {
        language: this.i18n.current(),
        period: this.period(),
        date: this.period() === "date" ? this.date() : "",
        department: this.department(),
        city: this.city(),
        category: this.category(),
        query: this.query(),
        freeOnly: String(this.freeOnly()),
      };
      untracked(() => {
        this.request?.unsubscribe();
        this.params = params;
        this.outings.set([]);
        this.total.set(0);
        this.hasMore.set(false);
        this.loading.set(true);
        this.loadingMore.set(false);
        this.error.set(false);
        this.moreError.set(false);
      });
      const timer = setTimeout(() => this.load(), 200);
      onCleanup(() => clearTimeout(timer));
    });
    this.destroyRef.onDestroy(() => {
      this.request?.unsubscribe();
      this.observer?.disconnect();
    });
  }
  load() {
    this.fetchPage(false);
  }
  loadMore(retry = false) {
    if (
      !this.hasMore() ||
      this.loading() ||
      this.loadingMore() ||
      (this.moreError() && !retry)
    )
      return;
    this.fetchPage(true);
  }
  private fetchPage(append: boolean) {
    this.request?.unsubscribe();
    const offset = append ? this.outings().length : 0;
    if (append) {
      this.loadingMore.set(true);
      this.moreError.set(false);
    } else {
      this.loading.set(true);
      this.error.set(false);
    }
    this.request = this.http
      .get<CatalogPage>("/api/catalog", {
        params: { ...this.params, limit: "40", offset: String(offset) },
      })
      .subscribe({
        next: (page) => {
          this.outings.update((items) =>
            append ? [...items, ...page.items] : page.items,
          );
          this.total.set(page.total);
          this.hasMore.set(page.hasMore);
          this.loading.set(false);
          this.loadingMore.set(false);
        },
        error: () => {
          if (this.response) this.response.status = 503;
          if (append) this.moreError.set(true);
          else this.error.set(true);
          this.loading.set(false);
          this.loadingMore.set(false);
        },
      });
  }
  explore() {
    this.results.nativeElement.focus({ preventScroll: true });
    this.results.nativeElement.scrollIntoView({ block: "start" });
  }
  chooseCategory(value: string) {
    this.category.set(value);
  }
  chooseDate(value: string) {
    this.date.set(value || "");
    this.period.set(value ? "date" : "today");
  }
  chooseCalendarDate(value: Date | null) {
    if (!value) return;
    // Keep the calendar day unchanged, including in timezones west of UTC.
    this.chooseDate(
      `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, "0")}-${String(value.getDate()).padStart(2, "0")}`,
    );
  }
  reset() {
    this.state.reset();
  }
  chooseDepartment(value: string) {
    this.department.set(value);
    if (!this.cities().some((city) => city.name === this.city()))
      this.city.set("");
  }
}
