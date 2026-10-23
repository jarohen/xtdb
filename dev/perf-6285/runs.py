import json, sys, statistics

for path in sys.argv[1:]:
    hot, cold = [], None
    for line in open(path):
        try:
            r = json.loads(line)
        except ValueError:
            continue
        stage = str(r.get('stage', ''))
        if 'q6' not in stage:
            continue
        if stage.startswith('cold'):
            cold = r['time-taken-ms']
        else:
            hot.append(r['time-taken-ms'])
    print(f"{path.rsplit('/', 1)[-1]:12} cold {cold:6} hot median {statistics.median(hot):7.0f} min {min(hot):6} hot {hot}")
