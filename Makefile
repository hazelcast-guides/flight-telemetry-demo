# docker-grafana-graphite makefile

# Environment Variables
CONTAINER = grafana-dashboard

.PHONY: up

prep :
	mkdir -p \
		data/whisper \
		data/elasticsearch \
		data/grafana \
		log/graphite \
		log/graphite/webapp \
		log/elasticsearch

clean : prep
	docker-compose build --no-cache && docker-compose up -d

up : prep
	docker-compose up -d

down :
	docker-compose down

shell :
	docker exec -ti $(CONTAINER) /bin/bash

tail :
	docker logs -f $(CONTAINER)
