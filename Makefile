# Сборка и доставка образа сервиса. Общий шаблон для всех репозиториев MediaFlux.
#
# Переменные:
#   REGISTRY       — адрес реестра с префиксом проекта. Пусто → образ остаётся локальным.
#   IMAGE_TAG      — тег. По умолчанию короткий git sha: видно, что именно запущено,
#                    а откат — это просто прежний тег (с `latest` ни того, ни другого).
#   DOCKER_CONTEXT — docker-контекст сборки. На стенде `maxix`: собираем НА НОДЕ по SSH,
#                    нативный amd64 вместо эмуляции на Apple Silicon (десятки секунд против минут).
#   PLATFORM       — целевая платформа, если нужна не родная для контекста сборки.
#
# Примеры:
#   make image                                                   # локально, тег = git sha
#   make push REGISTRY=localhost:32000/mediaflux DOCKER_CONTEXT=maxix
#   make image-name REGISTRY=registry.example.com/mediaflux       # имя для манифеста
IMAGE_NAME ?= app-api
REGISTRY   ?=
IMAGE_TAG  ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo dev)

DOCKER_CONTEXT ?=
PLATFORM       ?=

CTX_FLAG  := $(if $(DOCKER_CONTEXT),--context $(DOCKER_CONTEXT),)
PLAT_FLAG := $(if $(PLATFORM),--platform $(PLATFORM),)
IMAGE     := $(if $(REGISTRY),$(REGISTRY)/,)$(IMAGE_NAME):$(IMAGE_TAG)

.PHONY: help image push image-name
help: ## Показать список команд
	@grep -E '^[a-zA-Z_-]+:.*##' $(MAKEFILE_LIST) | sed -E 's/^([a-zA-Z_-]+):.*## /  \1\t/' | expand -t14

image: ## Собрать образ сервиса
	docker $(CTX_FLAG) build $(PLAT_FLAG) -t $(IMAGE) .

push: image ## Собрать и отправить образ в реестр
	@test -n "$(REGISTRY)" || { echo "REGISTRY не задан — пушить некуда"; exit 1; }
	docker $(CTX_FLAG) push $(IMAGE)

image-name: ## Напечатать полное имя образа (для скриптов и манифестов)
	@echo $(IMAGE)
