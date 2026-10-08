import { TablerIconsModule } from "angular-tabler-icons";
import {
  IconSearch,
  IconMenu2,
  IconCompass,
  IconCalendarEvent,
  IconTrees,
  IconBuildingBank,
  IconBasket,
  IconUsers,
  IconMusic,
  IconPalette,
  IconMapPin,
  IconArrowRight,
  IconX,
  IconClock,
  IconAdjustmentsHorizontal,
  IconChevronRight,
  IconSun,
  IconInfoCircle,
  IconShare,
  IconTicket,
  IconArrowUpRight,
} from "angular-tabler-icons/icons";
import { provideClientHydration, withEventReplay } from "@angular/platform-browser";
import { provideHttpClient, withFetch, withInterceptors } from "@angular/common/http";
import { ApplicationConfig, provideZoneChangeDetection, importProvidersFrom } from "@angular/core";
import { provideRouter, withInMemoryScrolling } from "@angular/router";
import { routes } from "./app.routes";
import { apiInterceptor } from "./rendering";
export const appConfig: ApplicationConfig = {
  providers: [
    importProvidersFrom(
      TablerIconsModule.pick({
        IconSearch,
        IconMenu2,
        IconCompass,
        IconCalendarEvent,
        IconTrees,
        IconBuildingBank,
        IconBasket,
        IconUsers,
        IconMusic,
        IconPalette,
        IconMapPin,
        IconArrowRight,
        IconX,
        IconClock,
        IconAdjustmentsHorizontal,
        IconChevronRight,
        IconSun,
        IconInfoCircle,
        IconShare,
        IconTicket,
        IconArrowUpRight,
      }),
    ),
    provideHttpClient(withFetch(), withInterceptors([apiInterceptor])),
    provideClientHydration(withEventReplay()),
    provideRouter(
      routes,
      withInMemoryScrolling({
        scrollPositionRestoration: "enabled",
        anchorScrolling: "enabled",
      }),
    ),
    provideZoneChangeDetection({ eventCoalescing: true }),
  ],
};
