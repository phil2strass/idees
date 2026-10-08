export interface Category {
  slug: string;
  name: string;
  icon: string;
}
export interface Occurrence {
  startsAt: string;
  endsAt: string;
  status: string;
  note?: string;
  allDay: boolean;
  startTimeKnown?: boolean;
  endTimeKnown?: boolean;
  timezone: string;
  city: string;
  placeName: string;
}
export interface Outing {
  contentLanguage?: string;
  translations?: {
    language: string;
    title: string;
    description_longue?: string | null;
    description_courte: string | null;
  }[];
  sourceDetails?: OutingSourceDetails | null;
  id: number;
  slug: string;
  url?: string;
  urls?: Partial<Record<string, string>>;
  title: string;
  summary: string;
  description: string;
  description_courte?: string | null;
  descriptions?: {
    language: string;
    description: string;
    description_longue: string | null;
    description_courte: string | null;
  }[];
  kind: string;
  city: string;
  department: string;
  placeName: string;
  address?: string | null;
  postalCode?: string | null;
  latitude?: number | null;
  longitude?: number | null;
  minAge: number;
  durationMinutes: number;
  environment: string;
  isDemo: boolean;
  practicalInfo: string;
  accessibility?: string | null;
  bookingUrl?: string | null;
  website?: string | null;
  sourceUrl?: string | null;
  sourceName?: string | null;
  images: {
    url: string;
    alt: string;
    credit?: string;
    license?: string;
    primary: boolean;
  }[];
  categories: Category[];
  prices: {
    label: string;
    amount: number | null;
    type: string;
    currency: string;
    conditions?: string | null;
  }[];
  schedules: { label: string }[];
  occurrences: Occurrence[];
}

export interface SourceContact {
  role: string;
  name: string | null;
  channels: { kind: string; value: string }[];
}
export interface SourceTranslation {
  language: string;
  title: string | null;
  summary: string | null;
  description: string | null;
  comment: string | null;
}
export interface SourceLocation {
  name: string | null;
  address: string | null;
  postalCode: string | null;
  city: string | null;
  department: string | null;
  latitude: number | null;
  longitude: number | null;
}
export interface OutingSourceDetails {
  reference: string | null;
  createdOn: string | null;
  updatedOn: string | null;
  updatedAt: string | null;
  contacts: SourceContact[];
  translations: SourceTranslation[];
  terms: { property: string; key: string | null; label: string | null }[];
  locations: SourceLocation[];
  resources: {
    url: string;
    mediaType: string | null;
    title: string | null;
    credit: string | null;
    license: string | null;
  }[];
}
