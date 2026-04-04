#!/bin/sh
fly deploy -c server/fly.toml --dockerfile server/Dockerfile .
