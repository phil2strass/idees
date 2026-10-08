import { Component, inject, RESPONSE_INIT } from "@angular/core";
import { RouterLink } from "@angular/router";
import { LanguageService } from "./language.service";
import { Title } from "@angular/platform-browser";
@Component({
  standalone: true,
  imports: [RouterLink],
  template: `<main id="content">
    <p>{{ t("ERREUR 404") }}</p>
    <h1>{{ t("Cette page a pris un autre chemin.") }}</h1>
    <p>
      {{
        t("Retrouvez toutes nos idées pour votre prochaine sortie en Alsace.")
      }}
    </p>
    <a [routerLink]="i18n.path('/')">{{ t("Revenir aux sorties →") }}</a>
  </main>`,
  styles: [
    `
      main {
        max-width: 900px;
        margin: 60px auto;
        padding: 24px;
      }
      h1 {
        font:
          600 34px Fraunces,
          Georgia,
          serif;
      }
      a {
        color: var(--red);
      }
    `,
  ],
})
export class NotFoundComponent {
  readonly i18n = inject(LanguageService);
  t(key: string) {
    return this.i18n.t(key);
  }
  constructor() {
    this.i18n.clearPageLinks();
    const response = inject(RESPONSE_INIT, { optional: true });
    if (response) response.status = 404;
    inject(Title).setTitle(
      this.t("Page introuvable") + " · " + this.t("Idées de sorties en Alsace"),
    );
  }
}
