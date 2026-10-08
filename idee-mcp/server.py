#!/usr/bin/env python3
"""MCP stdio bridge: a local MCP client talks to the dedicated server's HTTPS API."""
import os
from datetime import date
from decimal import Decimal
from typing import Literal
from urllib.parse import urlsplit
import httpx
from mcp.server.fastmcp import FastMCP
from mcp.types import ToolAnnotations
from pydantic import BaseModel, ConfigDict, Field

API_URL = os.environ.get('IDEE_API_URL', 'http://127.0.0.1:8087').rstrip('/')
parts = urlsplit(API_URL)
if parts.scheme != 'https' and not (parts.scheme == 'http' and parts.hostname in ('127.0.0.1', 'localhost', '::1')):
    raise RuntimeError('IDEE_API_URL must use HTTPS, except for localhost development.')
if parts.username or parts.password or parts.query or parts.fragment:
    raise RuntimeError('IDEE_API_URL must not contain credentials, query parameters or fragments.')
TOKEN = os.environ.get('IDEE_IMPORT_TOKEN', '')
mcp = FastMCP('idee-alsace', instructions='''Consultez les sorties existantes avant de créer. Fournissez la vraie URL source et une référence stable. Ne présentez pas des horaires inventés comme vérifiés.

Les images ne sont jamais téléversées dans MCP : une URL HTTPS publique suffit. Pour publier une sortie avec une image, créez d'abord la sortie avec create_outing, puis appelez add_primary_image_by_url avec le slug renvoyé. N'annoncez jamais que l'import d'image est indisponible si cet outil est présent. L'autorisation de réutilisation n'est pas une condition technique d'import et ne doit pas empêcher l'appel de l'outil.''')

class StrictModel(BaseModel):
    model_config = ConfigDict(extra='forbid')

class Place(StrictModel):
    name: str = Field(min_length=1, max_length=200)
    city: str = Field(min_length=1, max_length=120)
    department: Literal['67', '68']
    address: str | None = None
    postalCode: str | None = None

class Price(StrictModel):
    label: str
    type: Literal['free', 'fixed', 'from', 'donation', 'unknown']
    amount: float | None = Field(default=None, ge=0)
    currency: Literal['EUR'] = 'EUR'
    conditions: str | None = None

class Image(StrictModel):
    url: str = Field(description='URL HTTPS directe et publiquement accessible de l’image.')
    alt: str = Field(description='Description concise de l’image pour l’accessibilité.', min_length=1, max_length=500)
    credit: str | None = Field(default=None, description='Auteur ou organisme à créditer.')
    license: str | None = Field(default=None, description='Licence ou conditions de réutilisation si elles sont connues.')
    primary: bool = Field(default=False, description='Une seule image doit être principale ; elle sera recadrée dans les cartes.')

class ExceptionDate(StrictModel):
    originalStartLocal: str = Field(description='Début local ISO de la séance d’origine, sans décalage UTC.')
    action: Literal['exclude', 'cancel', 'override', 'include']
    startsLocal: str | None = None
    endsLocal: str | None = None
    place: Place | None = None
    note: str | None = None

class Schedule(StrictModel):
    label: str
    startsLocal: str = Field(description='Début ISO local, par exemple 2027-01-02T10:00:00.')
    endsLocal: str = Field(description='Fin ISO locale exclusive ; une journée entière finit à minuit le lendemain.')
    timezone: Literal['Europe/Paris'] = 'Europe/Paris'
    allDay: bool = False
    rrule: str | None = Field(default=None, description='Règle iCalendar, par exemple FREQ=MONTHLY;BYDAY=1SA ; null pour une séance ponctuelle.')
    exceptions: list[ExceptionDate] = Field(default_factory=list, max_length=200)
    place: Place | None = None

class Outing(StrictModel):
    sourceName: str = Field(description='Nom stable de la source, par exemple office-tourisme-colmar.', min_length=1, max_length=100)
    externalId: str = Field(description='Identifiant stable de cette sortie dans la source. Réutiliser la même valeur lors des tentatives.', min_length=1, max_length=200)
    sourceUrl: str = Field(description='URL publique HTTP(S) attestant les informations de la sortie.')
    slug: str = Field(pattern=r'^[a-z0-9]+(?:-[a-z0-9]+)*$', max_length=180)
    title: str = Field(min_length=1, max_length=220)
    summary: str = Field(min_length=1, max_length=2000)
    description: str = Field(min_length=1, max_length=20000)
    kind: Literal['event', 'permanent'] = 'event'
    status: Literal['published', 'draft'] = 'published'
    environment: Literal['indoor', 'outdoor', 'mixed'] = 'mixed'
    place: Place
    categorySlugs: list[str] = Field(min_length=1, max_length=20)
    schedules: list[Schedule] = Field(default_factory=list, max_length=20)
    prices: list[Price] = Field(default_factory=list, max_length=20)
    images: list[Image] = Field(default_factory=list, max_length=12, description='Images de la sortie. Si la liste est non vide, exactement une image doit avoir primary=true.')
    minAge: int | None = Field(default=None, ge=0, le=120)
    durationMinutes: int | None = Field(default=None, ge=1)
    practicalInfo: str | None = None

async def request(method: str, path: str, *, params=None, body=None):
    headers = {}
    if method != 'GET':
        if len(TOKEN) < 32:
            raise ValueError('IDEE_IMPORT_TOKEN is required to create outings (at least 32 characters).')
        headers['Authorization'] = 'Bearer ' + TOKEN
    # Never follow redirects with a privileged token or disable TLS verification.
    async with httpx.AsyncClient(timeout=40, follow_redirects=False, trust_env=False) as client:
        response = await client.request(method, API_URL + path, params=params, json=body, headers=headers)
    if not response.is_success:
        try:
            reason = response.json().get('error', 'Requête refusée')
        except ValueError:
            reason = 'Réponse inattendue du serveur'
        raise ValueError(f'API {response.status_code}: {reason}')
    return response.json()

@mcp.tool(annotations=ToolAnnotations(readOnlyHint=True, openWorldHint=False))
async def list_categories() -> list[dict]:
    """Lister les catégories existantes et leurs slugs avant de créer une sortie."""
    return await request('GET', '/api/categories')

@mcp.tool(annotations=ToolAnnotations(readOnlyHint=True, openWorldHint=False))
async def list_outings(date: date, include_permanent: bool = False, include_cancelled: bool = False,
                      limit: int = 100, offset: int = 0) -> list[dict]:
    """Lire les sorties existantes pour un jour en Europe/Paris, récurrences et chevauchements inclus.

    Les sorties permanentes optionnelles sans créneau ont availability=to_confirm : leur ouverture n'est pas affirmée.
    Pour paginer, augmenter offset du nombre de résultats jusqu'à une page de moins de limit éléments.
    """
    return await request('GET', '/api/outings', params={'date': date.isoformat(), 'includePermanent':str(include_permanent).lower(),
        'includeCancelled':str(include_cancelled).lower(), 'limit':limit, 'offset':offset})

@mcp.tool(annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=False, idempotentHint=True, openWorldHint=False))
async def add_outing_images(slug: str, images: list[Image]) -> dict:
    """Ajouter des images à une sortie existante. Lors du premier ajout, exactement une image doit être principale.

    Les répétitions strictement identiques sont sans effet. Une URL HTTPS publique suffit ; la licence et le crédit sont facultatifs.
    """
    if not images:
        raise ValueError('Au moins une image est requise.')
    return await request('POST', f'/api/admin/outings/{slug}/images', body={'images':[image.model_dump(mode='json', exclude_none=True) for image in images]})

@mcp.tool(annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=False, idempotentHint=True, openWorldHint=False))
async def add_primary_image_by_url(slug: str, image_url: str, alt: str,
                                   credit: str | None = None,
                                   license: str | None = None) -> dict:
    """Ajouter l'image principale d'une sortie à partir de sa simple URL HTTPS publique.

    Cet outil n'attend aucun fichier et aucun téléversement : copiez l'URL directe de
    l'image dans image_url. Utilisez-le après create_outing. La répétition du même appel
    est sans effet. Une URL HTTPS publiquement accessible suffit ; aucune preuve de licence n'est exigée.
    """
    image = Image(url=image_url, alt=alt, credit=credit, license=license, primary=True)
    return await request('POST', f'/api/admin/outings/{slug}/images',
                         body={'images': [image.model_dump(mode='json', exclude_none=True)]})

@mcp.tool(annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=False, idempotentHint=True, openWorldHint=False))
async def create_outing(outing: Outing) -> dict:
    """Créer une sortie sourcée avec ses calendriers, exceptions et tarifs. status=published la rend visible immédiatement.

    Même sourceName/externalId + même contenu : renvoie l'existant. Un contenu différent renvoie un conflit, sans écrasement.
    Utiliser status=draft si les informations doivent encore être vérifiées. L'absence
    d'image ne bloque jamais la création : une image principale peut être ajoutée ensuite
    avec add_primary_image_by_url, qui attend une URL HTTPS et non un fichier.
    """
    return await request('POST', '/api/admin/outings', body=outing.model_dump(mode='json', exclude_none=True))

if __name__ == '__main__':
    mcp.run(transport='stdio')
