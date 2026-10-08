// Population municipale : API Découpage administratif, consultée le 25/09/2026.
// https://geo.api.gouv.fr/departements/{67,68}/communes?fields=nom,code,population
// Les dix premières communes de chaque département suffisent aussi au top Alsace.
export const CATALOG_CITIES = [
  { name: "Strasbourg", department: "67", population: 293771 },
  { name: "Haguenau", department: "67", population: 36391 },
  { name: "Schiltigheim", department: "67", population: 34708 },
  { name: "Illkirch-Graffenstaden", department: "67", population: 27872 },
  { name: "Lingolsheim", department: "67", population: 20826 },
  { name: "Sélestat", department: "67", population: 19589 },
  { name: "Bischheim", department: "67", population: 18558 },
  { name: "Ostwald", department: "67", population: 13946 },
  { name: "Obernai", department: "67", population: 12587 },
  { name: "Bischwiller", department: "67", population: 12242 },
  { name: "Mulhouse", department: "68", population: 104978 },
  { name: "Colmar", department: "68", population: 66970 },
  { name: "Saint-Louis", department: "68", population: 22805 },
  { name: "Wittenheim", department: "68", population: 15553 },
  { name: "Illzach", department: "68", population: 14923 },
  { name: "Rixheim", department: "68", population: 14380 },
  { name: "Kingersheim", department: "68", population: 13354 },
  { name: "Riedisheim", department: "68", population: 12200 },
  { name: "Cernay", department: "68", population: 12057 },
  { name: "Guebwiller", department: "68", population: 11243 },
].sort((a, b) => b.population - a.population);
