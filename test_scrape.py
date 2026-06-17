import requests
import json

url = "https://streamed.pk/api/stream/admin/ppv-england-vs-croatia"
r = requests.get(url)
print(json.dumps(r.json(), indent=2))
