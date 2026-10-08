import { Component, inject, signal } from "@angular/core";
import { Router, RouterLink, RouterOutlet } from "@angular/router";
import { TablerIconsModule } from "angular-tabler-icons";
import { LanguageService } from "./language.service";
import { CatalogState } from "./catalog-state.service";
@Component({
  selector: "idee-root",
  standalone: true,
  imports: [RouterLink, RouterOutlet, TablerIconsModule],
  templateUrl: "./app.component.html",
  styleUrl: "./app.component.scss",
})
export class AppComponent {
  readonly i18n = inject(LanguageService);
  t(key: string) {
    return this.i18n.t(key);
  }
  readonly router = inject(Router);
  readonly state = inject(CatalogState);
  readonly menuOpen = signal(false);
  browse(period = "") {
    this.state.reset();
    this.state.period.set(period);
    this.menuOpen.set(false);
  }
}
