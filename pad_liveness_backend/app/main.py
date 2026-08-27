"""
MOSIP Face Liveness Detection & Presentation Attack Detection (PAD) service.

Backend for the Desktop and Android Registration Clients: receives face
frame streams, runs passive liveness + PAD, escalates to active
challenge-response when required, and enforces liveness/PAD policy per
workflow (resident registration, operator authentication, supervisor
authentication).
"""
import logging

from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.core.config import settings
from app.api.router import api_router
from app.services.image_utils import InvalidFrameError

logging.basicConfig(level=settings.LOG_LEVEL)
logger = logging.getLogger("pad_liveness")

app = FastAPI(
    title=settings.APP_NAME,
    version="1.0.0",
    description=(
        "Face liveness detection and Presentation Attack Detection (PAD) "
        "for MOSIP Registration Client workflows: resident registration, "
        "operator authentication, and supervisor authentication."
    ),
)

# Registration Clients (Desktop / Android) are the only expected callers;
# tighten allow_origins for production deployments.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.exception_handler(InvalidFrameError)
async def invalid_frame_handler(request: Request, exc: InvalidFrameError):
    return JSONResponse(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, content={"detail": str(exc)})


@app.get("/health", tags=["health"])
def health_check():
    return {"status": "ok", "service": settings.APP_NAME}


app.include_router(api_router)
