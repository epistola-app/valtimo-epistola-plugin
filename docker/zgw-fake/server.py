#!/usr/bin/env python3
"""
Minimal *stateful* fake of the ZGW Documenten + Zaken + Catalogi APIs — enough for the
Valtimo documentenapi/zakenapi/catalogiapi plugins to run the Epistola subsidy demo
WITHOUT a real OpenZaak (no PostGIS/Redis/Celery, native on arm64).

Drop-in for the local `zgw` profile: serves the same base paths on :8002, accepts any
Bearer token, keeps zaken / documents / links in memory. Modeled on real OpenZaak
response shapes. NOT for production and NOT a conformance substitute — it validates the
Valtimo-side chain and makes the case Documents tab show the generated PDFs.
"""
import json, re, uuid, base64
from datetime import date, datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = "http://localhost:8002"
CAT = f"{BASE}/catalogi/api/v1"
ZAK = f"{BASE}/zaken/api/v1"
DOC = f"{BASE}/documenten/api/v1"

# Catalogus data provisioned to match docker/openzaak/fixtures (same UUIDs/URLs the
# Valtimo config + subsidy process-link reference).
ZAAKTYPE_UUID = "59fa0613-b50d-41f1-8358-673909944686"
IOT_UUID = "cb6718f1-7b06-4dfc-9b11-1ba8db4740e6"
CATALOGUS_UUID = "d785f428-9ae6-4f84-82f6-26414500b2aa"

# In-memory state
ZAKEN = {}            # uuid -> zaak dict
DOCUMENTEN = {}       # uuid -> eio dict
DOC_CONTENT = {}      # uuid -> bytes
ZIO = {}              # uuid -> zaakinformatieobject dict


def now_dt():
    # Offset-free ISO (no trailing +00:00 / Z): the Valtimo zaken-api client deserializes
    # zaakinformatieobject.registratiedatum as java LocalDateTime, which rejects a zone offset.
    return datetime.now(timezone.utc).replace(tzinfo=None).isoformat()


def today():
    return date.today().isoformat()


def zaaktype_body():
    return {
        "url": f"{CAT}/zaaktypen/{ZAAKTYPE_UUID}",
        "identificatie": "aanvraag-financiele-ondersteuning-ondernemers-beha",
        "omschrijving": "Aanvraag financiele ondersteuning ondernemers behandelen",
        "omschrijvingGeneriek": "Aanvraag financiele ondersteuning ondernemers behandelen",
        "vertrouwelijkheidaanduiding": "zaakvertrouwelijk",
        "doel": "Financiele Ondersteuning Ondernemers",
        "aanleiding": "Externe aanvraag",
        "toelichting": "",
        "indicatieInternOfExtern": "extern",
        "handelingInitiator": "Aanvragen",
        "onderwerp": "Subsidie",
        "handelingBehandelaar": "Behandelen",
        "doorlooptijd": "P91D",
        "servicenorm": None,
        "opschortingEnAanhoudingMogelijk": False,
        "verlengingMogelijk": False,
        "verlengingstermijn": None,
        "trefwoorden": [],
        "publicatieIndicatie": False,
        "publicatietekst": "",
        "verantwoordingsrelatie": [],
        "productenOfDiensten": [],
        "selectielijstProcestype": "",
        "referentieproces": {"naam": "subsidie", "link": ""},
        "catalogus": f"{CAT}/catalogussen/{CATALOGUS_UUID}",
        "statustypen": [],
        "resultaattypen": [],
        "eigenschappen": [],
        "informatieobjecttypen": [f"{CAT}/informatieobjecttypen/{IOT_UUID}"],
        "roltypen": [],
        "besluittypen": [],
        "deelzaaktypen": [],
        "beginGeldigheid": "2020-01-01",
        "eindeGeldigheid": None,
        "versiedatum": "2020-01-01",
        "concept": False,
    }


def iot_body():
    return {
        "url": f"{CAT}/informatieobjecttypen/{IOT_UUID}",
        "catalogus": f"{CAT}/catalogussen/{CATALOGUS_UUID}",
        "omschrijving": "Subsidie document",
        "vertrouwelijkheidaanduiding": "openbaar",
        "beginGeldigheid": "2020-01-01",
        "eindeGeldigheid": None,
        "concept": False,
        "informatieobjectcategorie": "subsidie",
    }


def make_zaak(body):
    u = str(uuid.uuid4())
    seq = len(ZAKEN) + 1
    zaak = {
        "url": f"{ZAK}/zaken/{u}",
        "uuid": u,
        "identificatie": body.get("identificatie") or f"ZAAK-{date.today().year}-{seq:010d}",
        "bronorganisatie": body.get("bronorganisatie", "000000000"),
        "omschrijving": body.get("omschrijving", ""),
        "toelichting": body.get("toelichting", ""),
        "zaaktype": body.get("zaaktype", f"{CAT}/zaaktypen/{ZAAKTYPE_UUID}"),
        "registratiedatum": body.get("registratiedatum", today()),
        "verantwoordelijkeOrganisatie": body.get("verantwoordelijkeOrganisatie", body.get("bronorganisatie", "000000000")),
        "startdatum": body.get("startdatum", today()),
        "einddatum": None,
        "einddatumGepland": body.get("einddatumGepland"),
        "uiterlijkeEinddatumAfdoening": body.get("uiterlijkeEinddatumAfdoening"),
        "publicatiedatum": None,
        "communicatiekanaal": body.get("communicatiekanaal", ""),
        "productenOfDiensten": body.get("productenOfDiensten", []),
        "vertrouwelijkheidaanduiding": body.get("vertrouwelijkheidaanduiding", "zaakvertrouwelijk"),
        "betalingsindicatie": body.get("betalingsindicatie", ""),
        "betalingsindicatieWeergave": "",
        "laatsteBetaaldatum": None,
        "zaakgeometrie": body.get("zaakgeometrie"),
        "verlenging": None,
        "opschorting": {"indicatie": False, "eerdereOpschorting": False, "reden": ""},
        "selectielijstklasse": body.get("selectielijstklasse", ""),
        "hoofdzaak": None,
        "deelzaken": [],
        "relevanteAndereZaken": [],
        "eigenschappen": [],
        "rollen": [],
        "status": None,
        "zaakinformatieobjecten": [],
        "zaakobjecten": [],
        "kenmerken": [],
        "archiefnominatie": None,
        "archiefstatus": "nog_te_archiveren",
        "archiefactiedatum": None,
        "resultaat": None,
        "opdrachtgevendeOrganisatie": "",
        "processobjectaard": "",
        "startdatumBewaartermijn": None,
    }
    ZAKEN[u] = zaak
    return zaak


def make_eio(body):
    u = str(uuid.uuid4())
    seq = len(DOCUMENTEN) + 1
    inhoud_b64 = body.get("inhoud")
    if inhoud_b64:
        try:
            DOC_CONTENT[u] = base64.b64decode(inhoud_b64)
        except Exception:
            DOC_CONTENT[u] = b""
    eio = {
        "url": f"{DOC}/enkelvoudiginformatieobjecten/{u}",
        "uuid": u,
        "identificatie": body.get("identificatie") or f"DOCUMENT-{date.today().year}-{seq:010d}",
        "bronorganisatie": body.get("bronorganisatie", "000000000"),
        "creatiedatum": body.get("creatiedatum", today()),
        "titel": body.get("titel", "document"),
        "vertrouwelijkheidaanduiding": body.get("vertrouwelijkheidaanduiding", "openbaar"),
        "auteur": body.get("auteur", "GZAC"),
        "status": body.get("status", "definitief"),
        "formaat": body.get("formaat", "application/pdf"),
        "taal": body.get("taal", "nld"),
        "versie": 1,
        "beginRegistratie": now_dt(),
        "bestandsnaam": body.get("bestandsnaam", "document.pdf"),
        "inhoud": f"{DOC}/enkelvoudiginformatieobjecten/{u}/download",
        "bestandsomvang": len(DOC_CONTENT.get(u, b"")),
        "link": "",
        "beschrijving": body.get("beschrijving", ""),
        "ontvangstdatum": None,
        "verzenddatum": None,
        "indicatieGebruiksrecht": None,
        "ondertekening": {"soort": "", "datum": None},
        "integriteit": {"algoritme": "", "waarde": "", "datum": None},
        "informatieobjecttype": body.get("informatieobjecttype", f"{CAT}/informatieobjecttypen/{IOT_UUID}"),
        "locked": False,
        "bestandsdelen": [],
    }
    DOCUMENTEN[u] = eio
    return eio


def make_zio(body):
    u = str(uuid.uuid4())
    zio = {
        "url": f"{ZAK}/zaakinformatieobjecten/{u}",
        "uuid": u,
        "informatieobject": body.get("informatieobject"),
        "zaak": body.get("zaak"),
        "aardRelatieWeergave": "Hoort bij, omgekeerd: kent",
        "titel": body.get("titel", ""),
        "beschrijving": body.get("beschrijving", ""),
        "registratiedatum": now_dt(),
        "vernietigingsdatum": None,
        "status": None,
    }
    ZIO[u] = zio
    return zio


def page(results):
    return {"count": len(results), "next": None, "previous": None, "results": results}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):  # quieter
        pass

    def _send(self, code, body):
        data = json.dumps(body).encode() if body is not None else b""
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("API-version", "1.0.0")
        self.send_header("Content-Crs", "EPSG:4326")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if data:
            self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length", 0) or 0)
        if not n:
            return {}
        try:
            return json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return {}

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        query = self.path.split("?", 1)[1] if "?" in self.path else ""
        m = re.match(r"/catalogi/api/v1/zaaktypen/([0-9a-f-]+)$", path)
        if m:
            return self._send(200, zaaktype_body())
        if path == "/catalogi/api/v1/zaaktypen":
            return self._send(200, page([zaaktype_body()]))
        m = re.match(r"/catalogi/api/v1/informatieobjecttypen/([0-9a-f-]+)$", path)
        if m:
            return self._send(200, iot_body())
        if path == "/catalogi/api/v1/informatieobjecttypen":
            return self._send(200, page([iot_body()]))
        m = re.match(r"/zaken/api/v1/zaken/([0-9a-f-]+)$", path)
        if m:
            z = ZAKEN.get(m.group(1))
            return self._send(200 if z else 404, z or {"detail": "not found"})
        if path == "/zaken/api/v1/zaken":
            return self._send(200, page(list(ZAKEN.values())))
        if path == "/zaken/api/v1/zaakinformatieobjecten":
            from urllib.parse import parse_qs
            q = parse_qs(query)
            zaak = q.get("zaak", [None])[0]
            informatieobject = q.get("informatieobject", [None])[0]
            # Honor BOTH filters: the zaken-api client checks for an existing link with
            # ?zaak=&informatieobject= before creating one — filtering on zaak alone would
            # make it think every later document is already linked and skip it.
            items = [
                z for z in ZIO.values()
                if (zaak is None or z["zaak"] == zaak)
                and (informatieobject is None or z["informatieobject"] == informatieobject)
            ]
            return self._send(200, items)  # Zaken API returns a bare list for this collection
        m = re.match(r"/documenten/api/v1/enkelvoudiginformatieobjecten/([0-9a-f-]+)/download$", path)
        if m:
            content = DOC_CONTENT.get(m.group(1), b"%PDF-1.4 fake\n")
            self.send_response(200)
            self.send_header("Content-Type", "application/pdf")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            return
        m = re.match(r"/documenten/api/v1/enkelvoudiginformatieobjecten/([0-9a-f-]+)$", path)
        if m:
            d = DOCUMENTEN.get(m.group(1))
            return self._send(200 if d else 404, d or {"detail": "not found"})
        return self._send(404, {"detail": f"no GET stub for {path}"})

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        body = self._body()
        if path == "/zaken/api/v1/zaken":
            return self._send(201, make_zaak(body))
        if path == "/zaken/api/v1/zaakinformatieobjecten":
            return self._send(201, make_zio(body))
        if path == "/documenten/api/v1/enkelvoudiginformatieobjecten":
            return self._send(201, make_eio(body))
        return self._send(404, {"detail": f"no POST stub for {path}"})


if __name__ == "__main__":
    print("fake-zgw listening on :8002", flush=True)
    ThreadingHTTPServer(("0.0.0.0", 8002), Handler).serve_forever()
