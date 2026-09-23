#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Comprueba (y opcionalmente repara) el registro SDD de MySQL contra los specs
versionados en docs/sdd/ (plan de accion, Fase 9 / T44; ADR-0005).

Fuente de verdad: los specs. Cada docs/sdd/<subproyecto>/<slug>.md con su tabla de
metadatos (Dominio, Estado, ...) es una feature. La tabla `feature` de sdd_registry
debe tener una fila por spec, con spec_path y estado coherentes. Los eventos
(`feature_evento`) siguen siendo manuales: este script no los inventa.

Uso:
  python scripts/sdd-registry-check.py            # informa; exit 1 si hay diferencias
  python scripts/sdd-registry-check.py --apply    # ademas crea/actualiza las filas que faltan o difieren

Requiere el contenedor mysql-sdd levantado (external-services). Solo ASCII en la salida.
"""
import glob
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDD = os.path.join(ROOT, 'docs', 'sdd')
SUBPROYECTOS = ('customer', 'article', 'supplier', 'common')
ESTADOS = {
    'implementado': 'implementada', 'implementada': 'implementada',
    'implementado con brechas': 'implementada', 'implementada con brechas': 'implementada',
    'en diseño': 'en_diseno', 'en diseno': 'en_diseno', 'en curso': 'en_curso',
    'futuro': 'solicitada', 'descartada': 'descartada', 'descartado': 'descartada',
}


def specs():
    out = []
    for sub in SUBPROYECTOS:
        for path in sorted(glob.glob(os.path.join(SDD, sub, '*.md'))):
            name = os.path.basename(path)
            if name.upper() in ('CHANGELOG.MD', 'README.MD'):
                continue
            with open(path, encoding='utf-8') as f:
                text = f.read()
            title = next((l[2:].strip() for l in text.splitlines() if l.startswith('# ')), name)
            m = re.search(r'\|\s*\*\*Estado\*\*\s*\|\s*(.+?)\s*\|', text)
            raw = re.sub(r'[^\w\s]', '', m.group(1).split(':')[0]).strip().lower() if m else ''
            estado = next((v for k, v in ESTADOS.items() if raw.startswith(k)), 'implementada')
            out.append({
                'subproyecto': sub,
                'slug': name[:-3],
                'nombre': title,
                'estado': estado,
                'spec_path': 'docs/sdd/%s/%s' % (sub, name),
            })
    return out


def mysql(sql):
    cmd = ['docker', 'exec', 'mysql-sdd', 'mysql', '-usdd', '-psdd', '-N', '-B', 'sdd_registry', '-e', sql]
    env = dict(os.environ, MSYS_NO_PATHCONV='1')
    res = subprocess.run(cmd, capture_output=True, text=True, env=env)
    if res.returncode != 0:
        print('ERROR mysql:', res.stderr.strip()[:300])
        sys.exit(2)
    return [l.split('\t') for l in res.stdout.splitlines() if l and not l.startswith('mysql:')]


def q(v):
    return "'" + v.replace("'", "''") + "'"


def main():
    apply = '--apply' in sys.argv
    wanted = specs()
    rows = mysql("SELECT subproyecto, slug, estado, IFNULL(spec_path,''), nombre FROM feature WHERE eliminada_el IS NULL")
    db = {(r[0], r[1]): {'estado': r[2], 'spec_path': r[3], 'nombre': r[4]} for r in rows}
    diffs = []
    for w in wanted:
        key = (w['subproyecto'], w['slug'])
        have = db.get(key)
        if have is None:
            diffs.append(('FALTA', w, None))
        elif have['spec_path'] != w['spec_path'] or have['estado'] != w['estado']:
            diffs.append(('DIFIERE', w, have))
    orphans = [k for k, v in db.items() if v['spec_path'] and not os.path.exists(os.path.join(ROOT, v['spec_path']))]

    print('specs en docs/sdd: %d | filas en feature: %d | diferencias: %d | filas con spec_path roto: %d'
          % (len(wanted), len(db), len(diffs), len(orphans)))
    for kind, w, have in diffs:
        print(' %-8s %s/%s  spec=%s estado=%s%s' % (kind, w['subproyecto'], w['slug'], w['spec_path'], w['estado'],
              '' if have is None else '  (bd: spec=%s estado=%s)' % (have['spec_path'] or '-', have['estado'])))
    for k in orphans:
        print(' HUERFANA %s/%s  spec_path=%s no existe' % (k[0], k[1], db[k]['spec_path']))

    if apply and diffs:
        for kind, w, have in diffs:
            if kind == 'FALTA':
                mysql("INSERT INTO feature (subproyecto, slug, nombre, spec_path, estado, solicitada_por, solicitada_el, descripcion) VALUES (%s,%s,%s,%s,%s,'sdd-registry-check',CURDATE(),'Fila creada desde el spec por scripts/sdd-registry-check.py')"
                      % (q(w['subproyecto']), q(w['slug']), q(w['nombre']), q(w['spec_path']), q(w['estado'])))
            else:
                mysql("UPDATE feature SET spec_path=%s, estado=%s WHERE subproyecto=%s AND slug=%s"
                      % (q(w['spec_path']), q(w['estado']), q(w['subproyecto']), q(w['slug'])))
        print('aplicado: %d filas' % len(diffs))
        return 0
    return 1 if (diffs or orphans) else 0


if __name__ == '__main__':
    sys.exit(main())
