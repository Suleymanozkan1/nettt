-- AlterTable
ALTER TABLE "Run" ADD COLUMN     "challengeWeek" TEXT,
ADD COLUMN     "mode" TEXT NOT NULL DEFAULT 'normal',
ADD COLUMN     "startAct" INTEGER NOT NULL DEFAULT 1;

-- AlterTable
ALTER TABLE "User" ADD COLUMN     "maxAct" INTEGER NOT NULL DEFAULT 1,
ADD COLUMN     "onboarded" BOOLEAN NOT NULL DEFAULT false,
ADD COLUMN     "tokenVersion" INTEGER NOT NULL DEFAULT 0;

-- CreateTable
CREATE TABLE "DuelMatch" (
    "id" TEXT NOT NULL,
    "roomId" TEXT NOT NULL,
    "players" JSONB NOT NULL,
    "winnerId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DuelMatch_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "GameEvent" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "description" TEXT NOT NULL DEFAULT '',
    "startsAt" TIMESTAMP(3) NOT NULL,
    "endsAt" TIMESTAMP(3) NOT NULL,
    "fansMult" DOUBLE PRECISION NOT NULL DEFAULT 1,
    "creditsMult" DOUBLE PRECISION NOT NULL DEFAULT 1,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "GameEvent_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "DuelMatch_roomId_key" ON "DuelMatch"("roomId");

-- CreateIndex
CREATE INDEX "DuelMatch_winnerId_createdAt_idx" ON "DuelMatch"("winnerId", "createdAt");

-- CreateIndex
CREATE INDEX "GameEvent_startsAt_endsAt_idx" ON "GameEvent"("startsAt", "endsAt");

-- CreateIndex
CREATE INDEX "Run_mode_challengeWeek_status_score_idx" ON "Run"("mode", "challengeWeek", "status", "score");

-- AddForeignKey
ALTER TABLE "DuelMatch" ADD CONSTRAINT "DuelMatch_winnerId_fkey" FOREIGN KEY ("winnerId") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

