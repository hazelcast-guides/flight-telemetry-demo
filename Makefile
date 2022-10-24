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
	docker-compose --profile=${FLIGHT_TELEMETRY_HZ_INSTANCE_MODE} up -d

down :
	docker-compose --profile=${FLIGHT_TELEMETRY_HZ_INSTANCE_MODE} down

shell :
	docker exec -ti $(CONTAINER) /bin/bash

tail :
	docker logs -f $(CONTAINER)
