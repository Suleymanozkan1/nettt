FROM node:22-alpine AS base
RUN corepack enable && apk add --no-cache openssl
WORKDIR /app
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml tsconfig.base.json ./
COPY packages/shared/package.json packages/shared/
COPY apps/api/package.json apps/api/
RUN pnpm install --frozen-lockfile --filter @stage/api...
COPY packages/shared packages/shared
COPY apps/api apps/api
RUN pnpm --filter @stage/api exec prisma generate
ENV NODE_ENV=production PORT=3000
EXPOSE 3000
USER node
CMD ["sh", "-c", "cd apps/api && npx prisma migrate deploy && npx tsx src/server.ts"]
