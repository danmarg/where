# Stage 1: Build stage
# We use the full JDK image to compile the application and its dependencies (:shared).
FROM eclipse-temurin:21-jdk-alpine AS build

# The build context should be the root of the project to access :shared and gradle files.
# If you run `fly deploy` from the root, use:
#   fly deploy -c server/fly.toml --dockerfile server/Dockerfile .
WORKDIR /home/app

# Install dependencies needed for gradlew and JNI
RUN apk add --no-cache bash libc6-compat

# Copy the entire project context.
# We expect the build context to include the root project files, the 'server' module, 
# and the 'shared' module as they are interdependent.
COPY . .

# Ensure gradlew is executable and build the server distribution.
# Using --no-daemon is recommended for CI/CD and container builds.
RUN chmod +x ./gradlew && ./gradlew :server:installDist --no-daemon

# Stage 2: Runtime stage
# Use a smaller JRE image for the final container to keep it lightweight.
FROM eclipse-temurin:21-jre-alpine

# Set the working directory for the application.
WORKDIR /app

# Install native dependencies if required by the application or its crypto library.
# libc6-compat is often needed for JNI libraries in Alpine-based images.
RUN apk add --no-cache libc6-compat libsodium

# Copy the installed distribution from the build stage.
# The 'installDist' task puts the output in <project>/build/install/<project>.
COPY --from=build /home/app/server/build/install/server /app

# The application listens on port 8080 by default.
EXPOSE 8080

# Environment variables for the runtime.
# WHERE_DEBUG can be set to true if debug logging is needed.
ENV WHERE_DEBUG=false
ENV PORT=8080

# Execute the application. 
# The application plugin creates a start script in bin/ for the project name.
ENTRYPOINT ["/app/bin/server"]
