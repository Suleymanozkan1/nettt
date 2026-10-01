FROM node:22-alpine AS build
RUN corepack enable
WORKDIR /app
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml tsconfig.base.json ./
COPY packages/shared/package.json packages/shared/
COPY apps/game/package.json apps/game/
RUN pnpm install --frozen-lockfile --filter @stage/game...
COPY packages/shared packages/shared
COPY apps/game apps/game
ARG VITE_API_URL=http://localhost:3000
ENV VITE_API_URL=$VITE_API_URL
RUN pnpm --filter @stage/game exec vite build

FROM nginx:1.27-alpine
COPY --from=build /app/apps/game/dist /usr/share/nginx/html
EXPOSE 80
