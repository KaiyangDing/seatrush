package com.seatrush.common;

import com.seatrush.auth.InvalidCredentialsException;
import com.seatrush.auth.UsernameTakenException;
import com.seatrush.event.EventNotFoundException;
import com.seatrush.hold.HoldNotActiveException;
import com.seatrush.hold.HoldNotFoundException;
import com.seatrush.hold.InvalidHoldRequestException;
import com.seatrush.hold.SeatsUnavailableException;
import com.seatrush.lock.SeatsBusyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UsernameTakenException.class)
    ProblemDetail handleUsernameTaken(UsernameTakenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials(InvalidCredentialsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(EventNotFoundException.class)
    ProblemDetail handleEventNotFound(EventNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(SeatsUnavailableException.class)
    ProblemDetail handleSeatsUnavailable(SeatsUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("unavailableSeatIds", e.getSeatIds());
        return problem;
    }

    @ExceptionHandler(SeatsBusyException.class)
    ProblemDetail handleSeatsBusy(SeatsBusyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InvalidHoldRequestException.class)
    ProblemDetail handleInvalidHoldRequest(InvalidHoldRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HoldNotFoundException.class)
    ProblemDetail handleHoldNotFound(HoldNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(HoldNotActiveException.class)
    ProblemDetail handleHoldNotActive(HoldNotActiveException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
